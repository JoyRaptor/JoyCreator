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
    ['ground', 'path', { d: 'M24 120 L78 120' }, true, [50, 120]],
    ['tail', 'path', { d: 'M64 84 Q82 96 92 110' }, true, [95, 114]], ['legR', 'path', { d: 'M43 84 L41 112' }, true, [38, 116]], ['legL', 'path', { d: 'M57 84 L59 112' }, true, [62, 116]],
    ['armR', 'path', { d: 'M34 50 L17 76' }, true, [12, 80]], ['armL', 'path', { d: 'M66 50 L83 76' }, true, [88, 80]],
    ['body', 'rect', { x: 33, y: 43, width: 34, height: 42, rx: 13 }, false, [50, 64]], ['head', 'circle', { cx: 50, cy: 23, r: 17 }, false, [50, 23]],
    ['extra', 'rect', { x: 82, y: 6, width: 13, height: 13, rx: 4 }, false, [88.5, 12.5]]
  ];
  /* the same parts for something that stands on four legs: drawn side on, its front legs where a biped has arms */
  var SHAPES4 = [
    ['ground', 'path', { d: 'M22 117 L94 117' }, true, [58, 117]],
    ['tail', 'path', { d: 'M84 58 Q95 54 99 42' }, true, [99, 37]], ['legR', 'path', { d: 'M71 74 L69 106' }, true, [68, 111]], ['legL', 'path', { d: 'M80 74 L84 106' }, true, [86, 111]],
    ['armR', 'path', { d: 'M37 74 L33 106' }, true, [31, 111]], ['armL', 'path', { d: 'M46 74 L50 106' }, true, [52, 111]],
    ['body', 'rect', { x: 29, y: 48, width: 57, height: 28, rx: 13 }, false, [58, 62]], ['head', 'circle', { cx: 19, cy: 40, r: 15 }, false, [19, 40]],
    ['extra', 'rect', { x: 82, y: 6, width: 13, height: 13, rx: 4 }, false, [88.5, 12.5]]
  ];
  var ZL2 = { head: 'Head', body: 'Body', armL: 'Left arm', armR: 'Right arm', legL: 'Left leg', legR: 'Right leg', tail: 'Tail', extra: 'Extras', ground: 'Ground and walking' };
  var ZL4 = { head: 'Head', body: 'Body', armL: 'Front left leg', armR: 'Front right leg', legL: 'Back left leg', legR: 'Back right leg', tail: 'Tail', extra: 'Extras', ground: 'Ground and walking' };
  var ZLABEL = { head: 'Head', body: 'Body', armL: 'Left arm', armR: 'Right arm', legL: 'Left leg', legR: 'Right leg', tail: 'Tail', extra: 'Extras' };
  /* A character that has an all-fours stance is "on four legs" exactly while it stands in that stance: the switch in the card's header puts it
     there and stands it up again. One that was simply drawn on four legs only swaps the map. */
  function foursOf(app) { return app.ch && AL.stance ? AL.stance.fours(app.ch) : null; }
  function fourMap(app) { var fs = foursOf(app); if (fs) return app.live ? !!(app.inp && app.inp.trig[fs.trig]) : app.poseEdit === fs.id; return !!(app.ch && app.ch.settings && app.ch.settings.map === 'four'); }
  function fourSwitch(app) {
    var fs = foursOf(app);
    if (!fs) { app.edit(function () { if (fourMap(app)) delete app.ch.settings.map; else app.ch.settings.map = 'four'; }, false, 'Body map'); return; }
    if (app.live) { app.inp.toggleTrig(fs.trig); app.refreshPanel(true); return; }
    app.poseEdit = app.poseEdit === fs.id ? null : fs.id; app.fitOn = null; app.orderPick = null; app.userView = false; app.refreshPanel(true); app.focusSel();
  }
  function setLabels(app) { var src = fourMap(app) ? ZL4 : ZL2; for (var k in src) ZLABEL[k] = src[k]; }
  cards.bodyMap = function (app, big) {
    var svg = document.createElementNS(NS, 'svg'); svg.setAttribute('viewBox', big ? '-14 -4 128 136' : '0 0 104 124'); svg.setAttribute('class', 'bmap' + (big ? ' big' : ''));
    svg.setAttribute('role', 'group'); svg.setAttribute('aria-label', 'Body map'); setLabels(app);
    (fourMap(app) ? SHAPES4 : SHAPES).forEach(function (s) {
      var zone = s[0], has = zone === 'ground' ? true : zone === 'extra' ? cards.extras(app).length > 0 : !!app.zoneNode(zone);
      if (big && (zone === 'extra' || zone === 'ground')) return;
      var g = document.createElementNS(NS, 'g'); g.setAttribute('class', 'z' + (s[3] ? ' line' : '') + (has ? ' has' : '') + (!big && app.sel.zone === zone && app.tab === 'parts' ? ' sel' : ''));
      g.setAttribute('tabindex', '0'); g.setAttribute('role', 'button');
      var t = document.createElementNS(NS, 'title'); t.textContent = ZLABEL[zone] + (has ? '' : ': nothing here yet. Tap to add art.'); g.appendChild(t);
      var e = document.createElementNS(NS, s[1]); for (var k in s[2]) e.setAttribute(k, s[2][k]); e.setAttribute('class', 's'); g.appendChild(e);
      /* a fat invisible twin so a thin arm is easy to tap */
      var hit = e.cloneNode(); hit.setAttribute('class', ''); hit.setAttribute('style', 'fill:none;stroke:transparent;stroke-width:11;pointer-events:stroke'); if (!s[3]) hit.setAttribute('style', 'fill:transparent;stroke:transparent;stroke-width:6'); g.appendChild(hit);
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
  /** A small preview of a part, for any list or picker where a part is chosen by name. */
  cards.mini = function (node, px) { var c = thumb(node); c.className = 'mini'; c.style.width = c.style.height = (px || 22) + 'px'; return c; };
  function partChip(node, label, title, fn, on) { return h('button', { class: 'chip' + (on ? ' on' : ''), title: title || label, onclick: fn }, [node ? cards.mini(node, 22) : null, label]); }
  function pickPart(app, title, list, nameOf, fn, none) {
    var body = h('div', { class: 'menu' });
    if (none) body.appendChild(h('button', { title: none, onclick: function () { app.closeSheet(); fn(null); } }, [h('span', { text: none })]));
    list.forEach(function (k) { body.appendChild(h('button', { title: k.name, onclick: function () { app.closeSheet(); fn(k); } }, [cards.mini(k, 30), h('span', { text: nameOf ? nameOf(k) : k.name })])); });
    if (!list.length && !none) body.appendChild(app.ui.note('There is nothing else to choose from yet.'));
    app.sheet(title, body, []);
  }
  cards.pickPart = pickPart;
  /** What a part is called on a card: its place on the body if it is one (Head, Tail), otherwise the layer's own name. */
  cards.nameOf = function (app, n) { var z = AL.zoneOf(app.ch, n.id); return (z && z !== 'extra' && app.zoneNode(z) === n && ZLABEL[z]) || n.name; };
  function th(node, label, on, star, fn, title) { return h('button', { class: 'th' + (on ? ' on' : ''), title: title || label, onclick: fn }, [thumb(node), star ? h('i', { class: 'star', text: '★' }) : null, h('span', { text: label })]); }

  /* ── the panel ── */
  function head(app, title, badge, desc, badgeCls) {
    var ph = $('panelhead'); ph.innerHTML = '';
    ph.appendChild(cards.bodyMap(app, false));
    if (AL.stages.on()) {
      /* the stages layout: the name, then the four-way row of what you are doing, then (when the card has enough of them) shortcuts to its sections */
      var lay = app.ui.chip('', { on: app.tab === 'layers', icon: 'layers', cls: 'lay', title: app.tab === 'layers' ? 'Back to the card' : 'Every layer of the file, as a list', fn: function () { app.tab = app.tab === 'layers' ? 'parts' : 'layers'; app.refreshPanel(); } });
      ph.appendChild(h('div', { class: 'ptitle' }, [h('div', { class: 'ph1' }, [h('h2', null, [title, badge ? h('span', { class: 'badge ' + (badgeCls || ''), text: badge }) : null]), lay]), AL.stages.rail(app), desc ? h('p', { html: desc }) : null, h('div', { class: 'tabs' })]));
      return;
    }
    var tabs = h('div', { class: 'tabs' }, [
      app.ui.chip('Parts', { on: app.tab === 'parts', icon: 'person', title: 'Work part by part, from the body map', fn: function () { app.tab = 'parts'; app.refreshPanel(); } }),
      app.ui.chip('Layers', { on: app.tab === 'layers', icon: 'layers', title: 'Every layer of the file, as a list', fn: function () { app.tab = 'layers'; app.refreshPanel(); } }),
      app.ui.chip(fourMap(app) ? 'On four legs' : 'On two legs', { cls: 'quiet', title: foursOf(app) ? 'Tap to put it down on all fours, or to stand it up again. While it is on four legs, what you move is moved for that stance only.' : 'Which body map you pick parts from: something that stands on two legs, or on four. Tap to switch. (More legs than that are listed on the Body card.)', fn: function () { fourSwitch(app); } })
    ]);
    ph.appendChild(h('div', { class: 'ptitle' }, [h('h2', null, [title, badge ? h('span', { class: 'badge ' + (badgeCls || ''), text: badge }) : null]), desc ? h('p', { html: desc }) : null, tabs]));
  }
  cards.head = head;
  function tryBar(app, canTry) {
    var sb = $('stagebar'); sb.innerHTML = '';
    if (AL.stages.on()) return AL.stages.bar(app, sb);
    if (app.live) sb.appendChild(app.ui.chip('Done trying', { cls: 'veil on', icon: 'stop', title: 'Back to the still pose, where pins can be moved', fn: function () { app.setLive(false); } }));
    else if (canTry) sb.appendChild(app.ui.chip('Try it', { cls: 'veil', icon: 'play', title: 'Bring the character to life here: drag the head, the hands, the body', fn: function () { app.setLive(true); } }));
    if (app.live || !canTry) return;
    sb.appendChild(app.ui.chip('Pick a piece', { cls: 'veil' + (app.pickPiece ? ' on' : ''), icon: 'target', title: app.pickPiece ? 'On: tap any piece on the picture to work on it alone, with a box to move, turn and resize it. Tap here to switch it off.' : 'Then tap any piece on the picture: it is chosen by itself, everything else steps back, and a box lets you move, turn and resize it', fn: function () { app.pickPiece = !app.pickPiece; app.refreshPanel(true); } }));
    /* MOVE IT: the box for whatever is in hand, always one tap away on the stage */
    var mv = app.tab !== 'parts' ? null : app.sel.node ? AL.node(app.ch, app.sel.node) : app.sel.feature ? (app.featureNodes(app.sel.feature) || [])[0] : (app.sel.zone && app.sel.zone !== 'body' && app.sel.zone !== 'ground' ? app.zoneNode(app.sel.zone) : null);
    if (mv && AL.fit.can(app, mv)) {
      var mon = !!(app.fitOn && app.fitOn.box && app.fitOn.node === mv.id);
      sb.appendChild(app.ui.chip('Move it', { cls: 'veil' + (mon ? ' on' : ''), icon: 'move', title: 'A box on the picture for this part: drag inside it to move, a corner to resize, the round handle to turn', fn: function () { AL.fit.boxOn(app, mv, !mon); } }));
      if (mon && AL.fit.moved(app, mv)) sb.appendChild(app.ui.chip('Put it back', { cls: 'veil', title: 'Forget the move, the turn and the size: back to how it was drawn', fn: function () { app.edit(function () { AL.fit.reset(app, mv); }, false, 'Put it back'); } }));
    } else if (mv && mv.type === 'set' && mv.pick && mv.pick.by === 'reach') sb.appendChild(app.ui.chip('Move it', { cls: 'veil' + (app.moveArt ? ' on' : ''), icon: 'move', title: 'On: drag this limb on the picture to slide it, pins and all, to where it joins the body', fn: function () { app.moveArt = !app.moveArt; app.refreshPanel(true); } }));
    var pz = app.poseEdit && AL.stance.find(app.ch, app.poseEdit), tz = app.toneTry && AL.tone.find(app.ch, app.toneTry);
    if (pz) sb.appendChild(app.ui.chip(pz.name, { cls: 'veil on', icon: 'x', title: 'The character is standing in this stance, and what you move is moved for it. Tap to stand it up again.', fn: function () { app.poseEdit = null; app.fitOn = null; app.refreshPanel(true); } }));
    if (tz) sb.appendChild(app.ui.chip('Colours: ' + tz.name, { cls: 'veil on', icon: 'x', title: 'Showing this colour set. Tap to go back to its own colours.', fn: function () { app.toneTry = null; app.eyedrop = null; app.refreshPanel(true); } }));
  }
  function all(app, role, fn) { AL.byRole(app.ch.root, role).forEach(fn); }

  /* ── SECTIONS and the header's shortcuts ──
     A card is a list of titled sections, most used first. They are OPEN: nothing has to be hunted for. A long section can be folded
     away by its title and stays as it was left; a short one is simply there. The seldom-used ones (what a part follows, streams,
     masks) are gathered at the end behind one title, "More". The same titles sit in the card's header as shortcuts.
     Nothing about a card has to be written for this: it reads the titles the card already has. */
  var FOLDS = null, RARE = /^(Follows|Shapes|Size and turn|Carried by|Stream|Round shape|Masks|How it arrives|Its own turn drawings|In its hand|Swinging on the body|The part itself)/;
  function folds() { if (!FOLDS) { try { FOLDS = JSON.parse(localStorage.getItem('al.folds2') || '{}') || {}; } catch (e) { FOLDS = {}; } } return FOLDS; }
  function keepFolds() { try { localStorage.setItem('al.folds2', JSON.stringify(FOLDS)); } catch (e) { } }
  function shortName(t) { t = t.split(',')[0].split(':')[0]; return t.length > 18 ? t.slice(0, 17) + '\u2026' : t; }
  function isLab(k) { return !!(k && k.classList && k.classList.contains('lab')); }
  cards.fold = function (app) {
    var pb = $('panelbody'), ph = $('panelhead'), tabs = ph.querySelector('.tabs'), labs = Array.prototype.slice.call(pb.children).filter(isLab);
    var staged = AL.stages.on();
    if ((app.live && !staged) || app.bridge || app.tab === 'layers' || (!staged && labs.length < 2)) return;
    var h2 = ph.querySelector('h2'), card = h2 && h2.firstChild ? h2.firstChild.textContent : '', st = folds(), secs = [];
    labs.forEach(function (lab) {
      var name = lab.textContent, body = h('div', { class: 'secb' }), k = lab.nextSibling;
      while (k && !isLab(k)) { var nx = k.nextSibling; body.appendChild(k); k = nx; }
      pb.insertBefore(body, lab.nextSibling);
      secs.push({ name: name, lab: lab, body: body, rare: RARE.test(name) });
    });
    if (staged) secs = AL.stages.filter(app, secs, pb);
    var rare = secs.filter(function (s) { return s.rare; }), main = secs;
    if (rare.length >= 2) {
      var ml = app.ui.lab('More'), mb = h('div', { class: 'secb' });
      rare.forEach(function (s) { s.lab.classList.add('sub'); mb.appendChild(s.lab); mb.appendChild(s.body); });
      pb.appendChild(ml); pb.appendChild(mb);
      main = secs.filter(function (s) { return !s.rare; }); main.push({ name: 'More', lab: ml, body: mb, more: true });
    }
    main.forEach(function (s) {
      s.show = function () { };
      if (!s.more && s.body.offsetHeight < 150) return;                       // a short section is simply there
      var key = card + '|' + s.name.split(',')[0], open = st[key] != null ? !!st[key] : !s.more, lab = s.lab;
      function show(on, quiet) { open = on; lab.classList.toggle('shut', !on); s.body.style.display = on ? '' : 'none'; lab.setAttribute('aria-expanded', String(on)); if (!quiet) { st[key] = on ? 1 : 0; keepFolds(); } }
      lab.classList.add('fold'); lab.setAttribute('role', 'button'); lab.tabIndex = 0; lab.title = 'Show or hide \u201c' + s.name + '\u201d';
      lab.onclick = function () { show(!open); }; lab.onkeydown = function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); show(!open); } };
      show(open, true); s.show = show;
    });
    if (!tabs || main.length < 3) return;
    if (staged) { var dp = ph.querySelector('.ptitle p'); if (dp) dp.style.display = 'none'; }      // the shortcuts take the line the description had
    else { Array.prototype.slice.call(tabs.children, 0, 2).forEach(function (c) { c.classList.add('ico'); }); tabs.appendChild(h('i', { class: 'sep' })); }      // Parts and Layers shrink to their pictures, to leave the row to the shortcuts
    main.forEach(function (s) {
      tabs.appendChild(h('button', { class: 'chip jump', text: shortName(s.name), title: 'Go to \u201c' + s.name + '\u201d on this card', onclick: function () { s.show(true); pb.scrollTop = Math.max(0, s.lab.offsetTop - pb.offsetTop - 4); } }));
    });
  };

  cards.render = function (app) {
    var pb = $('panelbody'); pb.innerHTML = ''; app.onionOk = false; setLabels(app);
    if (app.fitOn && (!AL.node(app.ch, app.fitOn.node) || app.live)) app.fitOn = null;
    if (app.poseEdit && !AL.stance.find(app.ch, app.poseEdit)) app.poseEdit = null;
    if (app.toneTry && !AL.tone.find(app.ch, app.toneTry)) app.toneTry = null;
    if (app.shapeEdit && (app.sel.node !== app.shapeEdit.node || app.live)) app.shapeEdit = null;
    if (app.morph && !app.bridge) app.morph = null;
    if (app.ballTry && (app.sel.zone !== 'head' || app.sel.feature || app.sel.node || app.live)) app.ballTry = false;
    if (app.showLids && !(app.sel.zone === 'head' && app.sel.feature === 'eyes')) { app.showLids = false; app.show = {}; }
    var hasArt = !!AL.find(app.ch.root, function (n) { return n.type === 'art'; });
    $('empty').style.display = hasArt ? 'none' : 'flex';
    if (!hasArt) { $('stagebar').innerHTML = ''; return AL.intake.emptyPanel(app); }
    if (app.live && !AL.stages.is(app, 'motion')) { tryBar(app, true); head(app, 'Trying it', 'alive', 'Drag the <b>head</b> to turn it, a <b>hand</b> to reach, the <b>body</b> to lean. Double-tap a part to let it go back.'); app.coach('<b>Trying it.</b> Everything moves. Tap <b>Done trying</b> to go back to the pins.'); pb.appendChild(AL.perform.quick(app)); return; }
    if (app.bridge) return AL.bridge.render(app, pb);
    tryBar(app, true);
    if (app.tab === 'layers') return layers(app, pb);
    var z = app.sel.zone;
    var sn = app.sel.node ? AL.node(app.ch, app.sel.node) : null, sp = sn ? AL.parentOf(app.ch, sn.id) : null;
    function isLimb(q) { return !!(q && q.type === 'set' && q.pick && q.pick.by === 'reach' && q.pick.hand); }
    if (sn && sn.limb && isLimb(sp) && !AL.fit.pose(app)) { app.force[sp.id] = sn.id; sn = sp; }
    if (isLimb(sn) && !AL.fit.pose(app)) return armCard(app, pb, sn.role && ZL2[sn.role] ? sn.role : (sn.pick.hand.charAt(0) === 'F' ? 'legL' : 'armL'), sn);
    if (sn) return nodeCard(app, pb, sn);
    if (z === 'ground') return AL.ground.card(app, pb);
    if (z && z !== 'extra' && !app.zoneNode(z)) return extraCard(app, pb);            // this character has no such part (a hover-bot has no legs)
    /* in a stance, a part chosen on the body map gets the same card as one chosen with Pick a piece: where it sits and what it is in front of, for this stance */
    if (AL.fit.pose(app) && z !== 'body' && !app.sel.feature && app.zoneNode(z)) return nodeCard(app, pb, app.zoneNode(z));
    if (z === 'head') { if (app.sel.feature) return feature(app, pb, app.sel.feature); headCard(app, pb); AL.stack.section(app, pb, app.zoneNode('head'), { quiet: true }); return; }
    if (z === 'armL' || z === 'armR') return armCard(app, pb, z);
    if (z === 'body') return bodyCard(app, pb);
    if (z === 'tail') { tailCard(app, pb); AL.stack.section(app, pb, app.zoneNode('tail'), { quiet: true }); return; }
    if (z === 'legL' || z === 'legR') return legCard(app, pb, z);
    return extraCard(app, pb);
  };

  /* ── a pad you turn the head with; dots are the drawings ── */
  cards.turnPad = function (app, opts) {
    opts = opts || {};
    var c = h('canvas'), pad = h('div', { class: 'pad' + (opts.small ? ' sm' : ''), title: 'Drag to turn. Each dot is one drawing; a diamond between two dots matches them so the swap is smooth.' }, c), dragDot = null, pushed = false, brs = [];
    function theSet() { if (opts.set) return AL.node(app.ch, opts.set); var hd = app.zoneNode('head'); return hd && hd.type === 'set' ? hd : null; }
    function isLive() { return app.mode === 'perform' || app.live; }
    function dialOf(hs) { return hs && hs.pick ? hs.pick.dial : null; }
    function value() {
      var hs = theSet(), dn = dialOf(hs);
      if (isLive()) { if (dn) { var q = app.inp.S.dial && app.inp.S.dial[dn]; return q ? [q[0], q[1]] : [0, 0]; } return [app.inp.S.turnX, app.inp.S.turnY]; }
      if (dn) { var w = (app.btd || {})[dn]; return w ? [w[0], w[1]] : [0, 0]; }
      return [app.bt.x, app.bt.y];
    }
    function put(x, y) {
      var hs = theSet(), dn = dialOf(hs);
      if (isLive()) { if (dn) app.inp.pose.dial[dn] = [x, y]; else { app.inp.pose.turnX = x; app.inp.pose.turnY = y; } return; }
      if (dn) { app.btd = app.btd || {}; app.btd[dn] = [x, y]; } else { app.bt.x = x; app.bt.y = y; }
    }
    function toPx(v, size) { return (v * 0.4 + 0.5) * size; } function fromPx(p, size) { return clamp((p / size - 0.5) / 0.4, -1, 1); }
    function draw() {
      var size = pad.clientWidth || 132, dpr = root.devicePixelRatio || 1; if (c.width !== Math.round(size * dpr)) { c.width = c.height = Math.round(size * dpr); }
      var g = c.getContext('2d'), hs = theSet(), live = isLive(), v = value();
      g.setTransform(dpr, 0, 0, dpr, 0, 0); g.clearRect(0, 0, size, size);
      g.strokeStyle = '#2c2c35'; g.lineWidth = 1; g.beginPath(); g.moveTo(size / 2, 8); g.lineTo(size / 2, size - 8); g.moveTo(8, size / 2); g.lineTo(size - 8, size / 2); g.stroke();
      var pick = hs && app.out ? app.out.picks[hs.id] : null, cur = pick ? AL.node(app.ch, pick) : null; brs = [];
      if (hs && cur && !live && !app.bridge && opts.bridges !== false) AL.bridge.neighbours(hs, cur).forEach(function (o) {
        var x = toPx((cur.at[0] + o.at[0]) / 2, size), y = toPx((cur.at[1] + o.at[1]) / 2, size), has = AL.bridge.count(app.ch, hs, cur, o) > 0;
        g.beginPath(); g.moveTo(toPx(cur.at[0], size), toPx(cur.at[1], size)); g.lineTo(toPx(o.at[0], size), toPx(o.at[1], size)); g.strokeStyle = has ? 'rgba(244,63,142,.5)' : '#3a3a46'; g.lineWidth = 1; g.stroke();
        g.beginPath(); g.moveTo(x, y - 5); g.lineTo(x + 5, y); g.lineTo(x, y + 5); g.lineTo(x - 5, y); g.closePath(); if (has) { g.fillStyle = '#f43f8e'; g.fill(); } else { g.fillStyle = '#111114'; g.fill(); g.strokeStyle = '#a1a1aa'; g.lineWidth = 1.4; g.stroke(); }
        brs.push({ x: x, y: y, a: cur, b: o });
      });
      if (hs) hs.kids.forEach(function (k) {
        if (!k.at) return; var x = toPx(k.at[0], size), y = toPx(k.at[1], size), on = k.id === pick;
        g.beginPath(); g.arc(x, y, on ? 8 : 6, 0, 7);
        if (k.type === 'mirror') { g.strokeStyle = on ? '#22d3ee' : '#71717a'; g.lineWidth = 2; g.stroke(); } else { g.fillStyle = on ? '#22d3ee' : '#a1a1aa'; g.fill(); }
      });
      g.beginPath(); g.arc(toPx(v[0], size), toPx(v[1], size), 12, 0, 7); g.strokeStyle = live ? '#f43f8e' : '#e4e4e7'; g.lineWidth = 2; g.stroke();
    }
    (app.padDraws = app.padDraws || []).push(draw);
    function at(e) { var r = pad.getBoundingClientRect(), k = pad.clientWidth / r.width; return [(e.clientX - r.left) * k, (e.clientY - r.top) * k]; }
    function setTurn(p, snap) {
      var size = pad.clientWidth, x = fromPx(p[0], size), y = fromPx(p[1], size), hs = theSet();
      if (!isLive() && snap && hs) { var best = null, bd = 9; hs.kids.forEach(function (k) { if (!k.at) return; var d = Math.hypot(k.at[0] - x, k.at[1] - y); if (d < bd) { bd = d; best = k; } }); if (best) { x = best.at[0]; y = best.at[1]; } }
      put(x, y);
    }
    pad.addEventListener('pointerdown', function (e) {
      var p = at(e), size = pad.clientWidth, hs = theSet(); dragDot = null; pushed = false;
      for (var i = 0; i < brs.length; i++) if (Math.hypot(brs[i].x - p[0], brs[i].y - p[1]) < 11) { AL.bridge.open(app, hs, brs[i].a, brs[i].b); return; }
      pad.setPointerCapture(e.pointerId);
      if (opts.moveDots && opts.moveDots() && hs) hs.kids.forEach(function (k) { if (k.at && Math.hypot(toPx(k.at[0], size) - p[0], toPx(k.at[1], size) - p[1]) < 14) dragDot = k; });
      if (!dragDot) { if (isLive()) app.inp.held.head = true; setTurn(p, false); }
      pad._down = true;
    });
    pad.addEventListener('pointermove', function (e) {
      if (!pad._down) return; var p = at(e), size = pad.clientWidth;
      if (dragDot) { if (!pushed) { app.hist.push(app.ch, 'Move an angle dot'); app.syncUndo(); pushed = true; } dragDot.at = [Math.round(fromPx(p[0], size) * 20) / 20, Math.round(fromPx(p[1], size) * 20) / 20]; put(dragDot.at[0], dragDot.at[1]); app.touch(); }
      else setTurn(p, false);
    });
    function up(e) { if (!pad._down) return; pad._down = false; app.inp.held.head = false; if (!dragDot) { setTurn(at(e), true); } if (!isLive()) { setTimeout(function () { app.resolveSel(); app.userView = false; app.focusSel(); app.refreshPanel(true); }, 30); } }
    pad.addEventListener('pointerup', up); pad.addEventListener('pointercancel', up);
    return pad;
  };
  /** Every set of angles in the character: the head, and anything else that turns on its own pad. */
  cards.turnSets = function (app) { return AL.findAll(app.ch.root, function (n) { return n.type === 'set' && n.pick && n.pick.by === 'turn' && n.kids.length > 1; }); };

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
      pb.appendChild(ui.lab('Its angles', 'setup fit'));
      pb.appendChild(h('div', { class: 'padrow' }, [cards.turnPad(app, { moveDots: function () { return app.moveDots; } }), side]));
      angleTools(app, pb, hd, cv);
      pb.appendChild(ui.note('The small <b>diamonds</b> between two dots are bridges. Tap one to match those two drawings, so the head bends from one into the other instead of jumping.'));
    }
    var feats = Object.keys(app.FEAT).filter(function (f) { return app.featureNodes(f).length; });
    if (feats.length) { pb.appendChild(ui.lab(isSet ? 'On this angle' : 'Face parts', 'all')); pb.appendChild(ui.row(feats.map(function (f) { return partChip(app.featureNodes(f)[0], app.FEAT[f].label, 'Open ' + app.FEAT[f].label, function () { app.select('head', f); }); }))); }
    pb.appendChild(ui.lab('How it moves', 'motion'));
    if (isSet) {
      pb.appendChild(ui.slider('Depth', { min: 0, max: 30, value: hd.pick.depth == null ? 12 : hd.pick.depth, set: function (v) { hd.pick.depth = v; }, fmt: function (v) { return v + ' px'; }, title: 'How far the nose, eyes and frill slide between two angles. More depth feels rounder.' }));
      pb.appendChild(ui.slider('Cross-fade', { min: 0, max: 300, step: 10, value: hd.pick.fade == null ? 110 : hd.pick.fade, set: function (v) { hd.pick.fade = v; }, fmt: function (v) { return v ? v + ' ms' : 'cut'; }, title: 'How softly one angle gives way to the next' }));
    }
    var roll = (hd.links || []).filter(function (l) { return l.sig === 'roll'; })[0];
    if (roll) pb.appendChild(ui.slider('Tilt', { min: 0, max: 40, value: roll.amt, set: function (v) { roll.amt = v; }, fmt: function (v) { return v + '°'; }, title: 'How far the head can tip from the neck pin' }));
    var ball = hd.ball && hd.ball.on !== false ? hd.ball : null;
    if (hd.kids && hd.kids.length) {
      pb.appendChild(ui.lab('Round head', 'setup'));
      pb.appendChild(ui.toggle('Turn it like a ball', !!ball, function (v) { app.edit(function () { if (v) { if (hd.ball) hd.ball.on = true; else if (!AL.guessBall(app.ch, hd)) app.toast('There is no head drawing to make round yet.'); } else if (hd.ball) hd.ball.on = false; }); if (!v) { app.ballTry = false; app.bt.x = app.bt.y = 0; } }, 'The eyes, nose and mouth slide over a round head as it turns and squash toward its edge; ears and hair swing round behind. One drawing turns like many.'));
      if (ball) {
        pb.appendChild(ui.slider('Turns', { min: 0, max: 85, value: ball.yaw == null ? 55 : ball.yaw, set: function (v) { ball.yaw = v; }, fmt: function (v) { return v + '°'; }, title: 'How far round the head goes when it looks fully left or right' }));
        pb.appendChild(ui.slider('Nods', { min: 0, max: 60, value: ball.pitch == null ? 32 : ball.pitch, set: function (v) { ball.pitch = v; }, fmt: function (v) { return v + '°'; }, title: 'How far it tips when it looks fully up or down' }));
        roundExtra(app, pb, hd, ball);
        pb.appendChild(ui.toggle('Preview the turn here', !!app.ballTry, function (v) { app.ballTry = v; if (!v && !isSet) app.bt.x = app.bt.y = 0; app.refreshPanel(true); }, isSet ? 'On: dragging the pad shows the parts sliding' : 'On: the two sliders below turn the head'));
        if (app.ballTry && !isSet) {
          pb.appendChild(ui.slider('Look left / right', { min: -100, max: 100, value: Math.round(app.bt.x * 100), undo: false, set: function (v) { app.bt.x = v / 100; }, fmt: function (v) { return v < -4 ? 'left' : v > 4 ? 'right' : 'ahead'; } }));
          pb.appendChild(ui.slider('Look up / down', { min: -100, max: 100, value: Math.round(app.bt.y * 100), undo: false, set: function (v) { app.bt.y = v / 100; }, fmt: function (v) { return v < -4 ? 'up' : v > 4 ? 'down' : 'level'; } }));
        }
        pb.appendChild(ui.row([ui.chip('Guess the parts again', { icon: 'wand', title: 'Fit the ball to the face drawing again and decide again which parts are painted on and which stick out', fn: function () { app.edit(function () { AL.guessBall(app.ch, hd); }); app.toast('Fitted to the face drawing'); } })]));
        pb.appendChild(ui.note('Drag the three pins so the dashed oval sits on the head. Open any face part to say whether it is <b>painted on</b> the head, <b>sticks out</b> of it, or <b>stays put</b>.'));
      }
    }
    app.pinProvider = function () {
      var p = hd.pivot ? [{ key: 'neck', label: 'Neck', x: hd.pivot.x, y: hd.pivot.y, node: null, set: function (x, y) { hd.pivot.x = x; hd.pivot.y = y; } }] : [];
      if (ball && !app.ballTry) p.push({ key: 'bc', label: 'Middle of the head', x: ball.cx, y: ball.cy, node: null, color: '#fbbf24', oval: [ball.rx, ball.ry], set: function (x, y) { ball.cx = x; ball.cy = y; } },
        { key: 'bx', label: 'Side', x: ball.cx + ball.rx, y: ball.cy, node: null, color: '#fbbf24', set: function (x) { ball.rx = Math.max(8, Math.abs(x - ball.cx)); } },
        { key: 'by', label: 'Top', x: ball.cx, y: ball.cy - ball.ry, node: null, color: '#fbbf24', set: function (x, y) { ball.ry = Math.max(8, Math.abs(ball.cy - y)); } });
      return p;
    };
    if (app.fitOn && app.fitOn.box && cv && cv.kid && app.fitOn.node === cv.kid.id) app.pinProvider = function () { return AL.fit.pins(app, cv.kid); };
  }
  /**
   * COPY AN ANGLE. A head drawn level can stand in for the same head tipped up or down: copy its dot, drag the copy to where it
   * should show, and turn or resize the whole copy. It is a full copy (every layer, mask, pin and shape), so its pieces can then be
   * nudged on their own. It starts matched to the angle it came from, so the two turn into each other without a jump.
   */
  cards.copyAngle = function (app, set, kid) {
    var ch = app.ch, made = null;
    function spot() {
      var a = kid.at || [0, 0], tries = [[0, -0.5], [0, 0.5], [-0.5, 0], [0.5, 0], [-0.5, -0.5], [0.5, -0.5], [-0.5, 0.5], [0.5, 0.5], [0, -0.25], [0, 0.25]];
      for (var i = 0; i < tries.length; i++) { var x = a[0] + tries[i][0], y = a[1] + tries[i][1]; if (Math.abs(x) > 1 || Math.abs(y) > 1) continue; if (!set.kids.some(function (k) { return k.at && Math.hypot(k.at[0] - x, k.at[1] - y) < 0.2; })) return [x, y]; }
      return [clamp(a[0] + 0.15, -1, 1), clamp(a[1] - 0.15, -1, 1)];
    }
    function tri(n) { var b = AL.boundsOf(n); if (!b) return null; return [[b.x0, b.y0], [b.x1, b.y0], [(b.x0 + b.x1) / 2, b.y1]].map(function (p) { return { a: [p[0], p[1]], b: [p[0], p[1]] }; }); }
    app.edit(function () {
      var at = spot(), c2;
      if (kid.type === 'mirror') c2 = { id: AL.id('n'), name: kid.name, type: 'mirror', of: kid.of, rot: kid.rot || 0, sc: kid.sc == null ? 1 : kid.sc };
      else {
        var txt = JSON.stringify(kid), ids = []; AL.walk(kid, function (q) { ids.push(q.id); });
        ids.forEach(function (old) { txt = txt.split('"' + old + '"').join('"' + AL.id('n') + '"'); });       // every link inside the copy (masks, riders, rest picks) follows it
        c2 = JSON.parse(txt);
        var pv = kid.pivot || set.pivot || (function () { var b = AL.boundsOf(kid); return b ? { x: (b.x0 + b.x1) / 2, y: (b.y0 + b.y1) / 2 } : null; })();
        if (pv) { if (!kid.pivot) kid.pivot = { x: pv.x, y: pv.y }; c2.pivot = { x: pv.x, y: pv.y }; }
        /* matched from birth: the same spots on both, for the whole head and for every piece that warps on its own */
        var bs = set.bridges || (set.bridges = []), w = tri(kid); if (w) bs.push({ a: kid.id, b: c2.id, pts: w, meet: 0.5 });
        for (var key in (set.warpWith || {})) if (set.warpWith[key] === 'own') { var pc = AL.find(kid, function (q) { return q !== kid && AL.bridge.key(q) === key; }), pt = pc ? tri(pc) : null; if (pt) bs.push({ a: kid.id, b: c2.id, part: key, pts: pt, meet: 0.5 }); }
      }
      c2.name = String(kid.name).replace(/ copy( \d+)?$/, '') + ' copy'; var nn = 2, base = c2.name; while (set.kids.some(function (k) { return k.name === c2.name; })) c2.name = base + ' ' + nn++;
      c2.at = at; delete c2.auto; set.kids.push(c2); made = c2;
    }, false, 'Copy an angle');
    if (made) { app.moveDots = true; app.bt.x = made.at[0]; app.bt.y = made.at[1]; app.toast('Copied. Drag its dot to where it should show, then turn it with the slider.'); setTimeout(function () { app.resolveSel(); app.refreshPanel(true); }, 40); }
    return made;
  };
  function angleTools(app, pb, hd, cv) {
    var ui = app.ui, kid = cv && cv.kid; if (!kid || !kid.at) return;
    function pivot() { if (kid.type !== 'mirror' && !kid.pivot) { var b = AL.boundsOf(kid), p = hd.pivot || (b ? { x: (b.x0 + b.x1) / 2, y: (b.y0 + b.y1) / 2 } : null); if (p) kid.pivot = { x: p.x, y: p.y }; } }
    pb.appendChild(ui.lab('This angle: ' + kid.name, 'setup'));
    pb.appendChild(ui.row([
      ui.chip('Copy this angle', { icon: 'plus', title: 'Make a copy of this drawing as a new dot: for the same head tipped up or down. Drag the new dot where it should show, then turn the copy.', fn: function () { cards.copyAngle(app, hd, kid); } }),
      ui.chip('Rename', { cls: 'quiet', title: 'Give this angle another name', fn: function () { app.promptText('Name of this angle', kid.name, function (v) { app.edit(function () { kid.name = v; }, false, 'Rename an angle'); }); } }),
      hd.kids.filter(function (k) { return k.at; }).length > 1 ? ui.chip('Remove', { cls: 'quiet', title: 'Take this angle away. Undo brings it back.', fn: function () { app.edit(function () { hd.kids = hd.kids.filter(function (k) { return k !== kid && !(k.type === 'mirror' && k.of === kid.id); }); hd.bridges = (hd.bridges || []).filter(function (b) { return hd.kids.some(function (k) { return k.id === b.a; }) && hd.kids.some(function (k) { return k.id === b.b; }); }); if (hd.pick && hd.pick.rest === kid.id) hd.pick.rest = hd.kids[0].id; app.bt.x = app.bt.y = 0; }, false, 'Remove an angle'); setTimeout(function () { app.resolveSel(); app.refreshPanel(true); }, 40); } }) : null
    ]));
    pb.appendChild(ui.lab('Fit this angle', 'fit'));
    if (kid.type !== 'mirror') { var bx = !!(app.fitOn && app.fitOn.box && app.fitOn.node === kid.id); pb.appendChild(ui.row([ui.chip('Move, turn, resize on the picture', { on: bx, icon: 'move', cls: bx ? '' : 'go', title: 'A box round this whole angle: drag inside it to move every layer together, a corner to resize, the round handle below to turn', fn: function () { AL.fit.boxOn(app, kid, !bx); } }), AL.fit.moved(app, kid) ? ui.chip('Put it back', { cls: 'quiet', title: 'Forget the move, the turn and the size of this angle', fn: function () { app.edit(function () { AL.fit.reset(app, kid); }, false, 'Put an angle back'); } }) : null])); }
    pb.appendChild(ui.slider('Tip the whole angle', { min: -60, max: 60, step: 1, value: kid.rot || 0, set: function (v) { pivot(); kid.rot = v; }, fmt: function (v) { return v + '°'; }, title: 'Turns every layer of this angle together, from the neck. Between two dots the tip blends.' }));
    pb.appendChild(ui.slider('Size of the whole angle', { min: 60, max: 160, step: 1, value: Math.round((kid.sc == null ? 1 : kid.sc) * 100), set: function (v) { pivot(); kid.sc = v / 100; }, fmt: function (v) { return v + '%'; }, title: 'Makes every layer of this angle bigger or smaller together' }));
  }
  function mirrorChip(app, hd) {
    var auto = hd.kids.filter(function (k) { return k.type === 'mirror'; }), L = hd.kids.some(function (k) { return k.type !== 'mirror' && k.at && k.at[0] < -0.1; }), R = hd.kids.some(function (k) { return k.type !== 'mirror' && k.at && k.at[0] > 0.1; });
    if (auto.length) return app.ui.chip('Remove the ' + auto.length + ' mirrored', { cls: 'quiet', icon: 'flip', title: 'Take away the angles that were made by mirroring', fn: function () { app.edit(function () { hd.kids = hd.kids.filter(function (k) { return k.type !== 'mirror'; }); app.bt.x = 0; app.bt.y = 0; }); } });
    if (L !== R) return app.ui.chip('Mirror the other side', { icon: 'flip', title: 'Make the missing side by flipping the drawings you have', fn: function () { app.edit(function () { hd.kids.slice().forEach(function (k) { if (k.at && Math.abs(k.at[0]) > 0.1) hd.kids.push({ id: AL.id('n'), name: k.name + ' (mirrored)', type: 'mirror', of: k.id, at: [-k.at[0], k.at[1]], auto: true }); }); }); } });
    return null;
  }

  /** Each head angle is its own stack of layers, so "in front" and "behind" are set per angle: an ear can be in front of the head on one side and behind it on the other. */
  function orderChips(app, pb, nodes) {
    var ui = app.ui, ch = app.ch; nodes = nodes.filter(function (n) { var p = AL.parentOf(ch, n.id); return p && p.type === 'group' && p.kids.length > 1; });
    if (!nodes.length) return;
    pb.appendChild(ui.lab(AL.ancestorWith(ch, nodes[0].id, function (a) { return !!a.at; }) ? 'In front or behind, at this angle' : 'In front or behind', 'fit'));
    nodes.forEach(function (n) {
      var par = AL.parentOf(ch, n.id), i = par.kids.indexOf(n), face = par.kids.filter(function (k) { return k.role === 'face'; })[0], fi = face ? par.kids.indexOf(face) : -1;
      function place(fn) { app.edit(function () { var k = par.kids.filter(function (x) { return x !== n; }); k.splice(fn(k), 0, n); par.kids = k; }, false, 'Reorder ' + n.name); }
      var row = [nodes.length > 1 ? h('span', { class: 'note', style: 'margin:0;min-width:78px', text: n.name }) : null];
      if (face && face !== n) {
        row.push(ui.chip('In front of the face', { on: i < fi, fn: function () { place(function (k) { return k.indexOf(face); }); } }));
        row.push(ui.chip('Behind the face', { on: i > fi, fn: function () { place(function (k) { return k.indexOf(face) + 1; }); } }));
      }
      row.push(ui.chip('\u25b2', { title: 'One layer forward', disabled: i === 0, fn: function () { place(function () { return Math.max(0, i - 1); }); } }));
      row.push(ui.chip('\u25bc', { title: 'One layer back', disabled: i === par.kids.length - 1, fn: function () { place(function (k) { return Math.min(k.length, i + 1); }); } }));
      pb.appendChild(ui.row(row));
    });
  }
  function backToHead(app, pb, f) { var hd = app.zoneNode('head'); pb.appendChild(h('div', { class: 'rowf', style: 'align-items:flex-start' }, [app.ui.chip('Head', { icon: 'back', cls: 'quiet', title: 'Back to the whole head', fn: function () { app.select('head'); } }), h('span', { class: 'badge', text: ((app.curView() || {}).kid || {}).name || '' }), h('span', { style: 'flex:1' }), hd && hd.type === 'set' ? cards.turnPad(app, { small: true, bridges: false }) : null])); void f; }

  function feature(app, pb, f) {
    var ui = app.ui, cv = app.curView(), nodes = app.featureNodes(f), F = app.FEAT[f];
    backToHead(app, pb, f);
    var mir = mirrorNote(app, pb, cv);
    if (f === 'mouth') return mouthCard(app, pb, nodes[0], cv, mir);
    if (f === 'eyes') {
      head(app, 'Eyes', null, 'The pupils drift toward where the head is going, and the eyes blink by themselves.');
      app.coach('<b>Eyes.</b> Set how far the pupils travel, and how often they blink.');
      var pup = AL.byRole(app.ch.root, 'pupil').filter(function (p) { return p.look; }), rx = pup.length ? pup[0].look.rx : 0, st = app.ch.settings;
      pb.appendChild(ui.lab('Looking', 'motion'));
      if (pup.length) pb.appendChild(ui.slider('Pupils move', { min: 0, max: 14, step: 0.5, value: round1(rx), set: function (v) { pup.forEach(function (p) { p.look.rx = v; p.look.ry = v * 0.65; }); }, fmt: function (v) { return v + ' px'; }, title: 'How far a pupil slides when the eyes look to the side' }));
      pb.appendChild(ui.slider('Try: look', { min: -1, max: 1, step: 0.05, value: app.test.lookX, undo: false, set: function (v) { app.test.lookX = v; }, fmt: function (v) { return v < -0.05 ? 'left' : v > 0.05 ? 'right' : 'ahead'; } }));
      pb.appendChild(ui.lab('Blinking', 'motion'));
      pb.appendChild(ui.toggle('Blink by itself', st.blinkAuto !== false, function (v) { app.edit(function () { st.blinkAuto = v; }); }, 'Uneven, like a living thing: sometimes twice'));
      pb.appendChild(ui.slider('About every', { min: 1.5, max: 9, step: 0.1, value: st.blinkEvery || 3.6, set: function (v) { st.blinkEvery = v; }, fmt: function (v) { return v + ' s'; } }));
      pb.appendChild(ui.toggle('Eyes lead the head', st.eyesLead !== false, function (v) { app.edit(function () { st.eyesLead = v; }); }, 'The eyes get there first, then the head follows'));
      var off = []; nodes.forEach(function (e) { AL.blinkOffsets(e).forEach(function (q) { if (Math.abs(q.dx) > Math.max(4, q.w * 0.3)) off.push(q); }); });
      pb.appendChild(ui.row([ui.chip('Blink now', { icon: 'eye', fn: function () { app.test.blink = 1; setTimeout(function () { app.test.blink = 0; }, 260); } }),
        ui.chip('Hold the blink', { on: app.test.blink === 1 && app.holdBlink, title: 'Keep the eyes shut, to see where the blink drawing sits', fn: function () { app.holdBlink = !app.holdBlink; app.test.blink = app.holdBlink ? 1 : 0; app.refreshPanel(true); } }),
        off.length ? ui.chip('Centre the blink on the eye', { icon: 'wand', cls: 'go', title: 'At this angle the blink drawing sits ' + Math.round(Math.abs(off[0].dx)) + ' px to one side of the pupil. Slide it onto the eye.', fn: function () { app.edit(function () { off.forEach(function (q) { q.blink.x += Math.round(q.dx); }); }); app.toast('Blink centred at this angle'); } }) : null]));
      pb.appendChild(ui.lab('Eyelids', 'setup motion'));
      var lm = AL.lidMode(app.ch), lids = AL.byRole(app.ch.root, 'lidU').concat(AL.byRole(app.ch.root, 'lidL'));
      if (!lm) {
        pb.appendChild(ui.note('An upper lid that comes down (relaxed), tilts (angry, or sad), and a lower lid that pushes up like a cheek (cheery). No art needed.'));
        pb.appendChild(ui.row([ui.chip('Add eyelids', { cls: 'go', icon: 'plus', fn: function () { app.edit(function () { AL.addLids(app.ch); }); } })]));
      } else {
        var hasBall = !!AL.firstRole(app.ch.root, 'eyeball'), up = lids.filter(function (l) { return l.lid.kind === 'upper'; }), lo = lids.filter(function (l) { return l.lid.kind === 'lower'; });
        pb.appendChild(h('div', { class: 'sl' }, [h('label', { text: 'The lids are' }), ui.seg([['cut', 'Invisible', 'The lid hides the eye where it is, so the face painted underneath shows'], ['drawn', 'Drawn', hasBall ? 'A skin-coloured lid, kept inside the white of the eye' : 'Needs a layer for the white of the eye to stay inside']], lm, function (v) { if (v === 'drawn' && !hasBall) { app.toast('Drawn lids need a white of the eye to stay inside.'); return; } app.edit(function () { AL.addLids(app.ch, v); }); }, 'Is the lid something you see, or something that hides the eye?'), h('span')]));
        pb.appendChild(ui.slider('Try: relaxed', { min: 0, max: 1, step: 0.02, value: app.test.lidUp, undo: false, set: function (v) { app.test.lidUp = v; }, fmt: function (v) { return Math.round(v * 100) + '%'; }, title: 'The upper lid comes down' }));
        pb.appendChild(ui.slider('Try: angry / sad', { min: -1, max: 1, step: 0.05, value: app.test.lidTilt, undo: false, set: function (v) { app.test.lidTilt = v; if (v && app.test.lidUp < 0.25) app.test.lidUp = 0.3; }, fmt: function (v) { return v > 0.05 ? 'angry' : v < -0.05 ? 'sad' : 'level'; }, title: 'The upper lid tilts' }));
        pb.appendChild(ui.slider('Try: cheery', { min: 0, max: 1, step: 0.02, value: app.test.lidLow, undo: false, set: function (v) { app.test.lidLow = v; }, fmt: function (v) { return Math.round(v * 100) + '%'; }, title: 'The lower lid pushes up, as a cheek does in a smile' }));
        if (up.length) pb.appendChild(ui.slider('Tilt reach', { min: 0, max: 45, value: up[0].lid.tilt, set: function (v) { lids.forEach(function (l) { l.lid.tilt = v; }); }, fmt: function (v) { return v + '\u00b0'; }, title: 'How far the lid can tilt' }));
        if (lo.length) pb.appendChild(ui.slider('Cheek reach', { min: 0, max: 150, value: Math.round(lo[0].lid.travel / Math.max(1, lo[0].lid.base || (lo[0].lid.base = lo[0].lid.travel)) * 100), set: function (v) { lo.forEach(function (l) { l.lid.base = l.lid.base || l.lid.travel; l.lid.travel = l.lid.base * v / 100; }); }, fmt: function (v) { return v + '%'; }, title: 'How far the lower lid can rise' }));
        pb.appendChild(ui.toggle('Blink by closing the lids', !!st.blinkLids, function (v) { app.edit(function () { st.blinkLids = v; }); }, 'Off: a blink swaps to the blink drawing'));
        pb.appendChild(ui.row([
          ui.chip('Show the lids', { on: !!app.showLids, icon: 'eye', title: 'See the invisible lids as shapes, to check where they sit', fn: function () { app.showLids = !app.showLids; app.show = {}; if (app.showLids) lids.forEach(function (l) { app.show[l.id] = true; }); app.refreshPanel(true); } }),
          ui.chip('Remove eyelids', { cls: 'quiet', icon: 'trash', fn: function () { app.edit(function () { AL.removeLids(app.ch); app.test.lidUp = app.test.lidLow = app.test.lidTilt = 0; }); } })
        ]));
        var eyesHere = nodes.filter(function (e) { return e.type !== 'art' && !e.hidden && e.kids.some(function (k) { return k.lid; }); });
        if (eyesHere.length) {
          pb.appendChild(ui.note('The two eyes tilt as a mirrored pair at every angle. If one tilts the wrong way <b>at this angle</b>, turn it over:'));
          pb.appendChild(ui.row(eyesHere.map(function (e) { return ui.chip((AL.ROLES[e.role] || {}).label + ': tilt the other way', { on: !!e.lidFlip, icon: 'flip', fn: function () { app.edit(function () { e.lidFlip = !e.lidFlip; e.kids.forEach(function (k) { if (k.lid) k.lid.sign = -k.lid.sign; }); }); } }); })));
        }
      }
      var hidden = nodes.filter(function (n) { return n.hidden; });
      hidden.forEach(function (n) { pb.appendChild(ui.toggle('Show the ' + (AL.ROLES[n.role] || {}).label.toLowerCase() + ' at this angle', false, function () { app.edit(function () { delete n.hidden; }); }, 'It was hidden in the file, as if turned away')); });
      return;
    }
    if (f === 'brows') {
      head(app, 'Brows', null, 'They lift with surprise and when the mouth opens wide.'); app.coach('<b>Brows.</b> Set how far they lift.');
      var br = AL.byRole(app.ch.root, 'browL').concat(AL.byRole(app.ch.root, 'browR')), amt = br.length && br[0].links ? -br[0].links[0].amt : 4;
      pb.appendChild(ui.lab('Brows', 'motion'));
      pb.appendChild(ui.slider('Lift', { min: 0, max: 16, step: 0.5, value: round1(amt), set: function (v) { br.forEach(function (b) { if (b.links && b.links[0]) b.links[0].amt = -v; }); }, fmt: function (v) { return v + ' px'; } }));
      pb.appendChild(ui.slider('Try', { min: -1, max: 1, step: 0.05, value: app.test.brow, undo: false, set: function (v) { app.test.brow = v; }, fmt: function (v) { return v > 0.05 ? 'up' : v < -0.05 ? 'down' : 'rest'; } }));
      return;
    }
    if (f === 'jaw') {
      head(app, 'Jaw', null, 'Drops a little as the mouth opens, so the chin joins in.'); app.coach('<b>Jaw.</b> Set how far it drops with the mouth.');
      var jw = AL.byRole(app.ch.root, 'jaw'), ja = jw.length && jw[0].links ? jw[0].links[0].amt : 3;
      pb.appendChild(ui.lab('Jaw', 'motion'));
      pb.appendChild(ui.slider('Drops', { min: 0, max: 14, step: 0.5, value: ja, set: function (v) { jw.forEach(function (j) { if (j.links && j.links[0]) j.links[0].amt = v; }); }, fmt: function (v) { return v + ' px'; } }));
      var jhd = app.zoneNode('head'), jks = jhd && jhd.type === 'set' ? jhd.kids.filter(function (k) { return k.type !== 'mirror' && AL.fit.jawParts(k); }) : (jhd && AL.fit.jawParts(jhd) ? [jhd] : []);
      if (jks.length) { var jon = jks.every(function (k) { return AL.fit.hasJawDrop(k); }); pb.appendChild(ui.toggle('The face stretches with it', jon, function (v) { app.edit(function () { jks.forEach(function (k) { AL.fit.jawDrop(k, v, Math.max(6, ja * 2)); }); }, false, 'Face stretches as it talks'); }, 'A small mouth drawn from the side opens only a little. On: the lower face drops with the jaw at every angle, so talking reads from any side. It is a Shape called Talking on each face drawing; open the face to change it.')); }
      mouthTry(app, pb);
      return;
    }
    head(app, F.label, null, 'Slides a little more or less than the face as the head turns, which is what makes a flat drawing feel round.');
    app.coach('<b>' + F.label + '.</b> Things in front slide further; things behind slide the other way.');
    var role = F.roles[0], list = AL.byRole(app.ch.root, role), dp = list.length ? (list[0].depth || 0) : 0;
    pb.appendChild(ui.lab('How far it slides', 'motion'));
    pb.appendChild(ui.slider('Depth', { min: -1.5, max: 1.5, step: 0.1, value: dp, set: function (v) { list.forEach(function (n) { n.depth = v; }); }, fmt: function (v) { return v > 0.05 ? 'front ' + v : v < -0.05 ? 'behind ' + (-v) : 'flat'; }, title: 'Front slides with the turn, behind slides against it' }));
    nodes.forEach(function (n) { AL.fit.section(app, pb, n, nodes.length > 1 ? n.name : ''); });
    orderChips(app, pb, nodes);
    var first = nodes[0] ? (nodes[0].type === 'art' ? nodes[0] : AL.find(nodes[0], function (k) { return k.type === 'art'; })) : null;
    if (first && f === 'hair') {
      pb.appendChild(ui.lab(nodes.length > 1 ? 'Swinging (\u201c' + first.name + '\u201d)' : 'Swinging', 'motion'));
      swingControls(app, pb, first);
      if (first.swing) pb.appendChild(ui.row([ui.chip('Same on every angle', { title: 'Copy these settings to the same-named layer at every head angle', fn: function () { app.edit(function () { AL.findAll(app.ch.root, function (k) { return k.type === 'art' && k !== first && k.name === first.name && k.role === first.role; }).forEach(function (k) { var vw = AL.ancestorWith(app.ch, k.id, function (a) { return !!a.at; }), vb = AL.boundsOf(vw || k); AL.guessSwing(app.ch, k, vb ? { x: (vb.x0 + vb.x1) / 2, y: (vb.y0 + vb.y1) / 2 } : null, first.swing.kind); ['segs', 'stiff', 'damp', 'follow', 'hang', 'limit', 'wind', 'flutter', 'wag', 'on'].forEach(function (key) { k.swing[key] = first.swing[key]; }); }); }); app.toast('Copied to every angle'); } })]));
      app.pinProvider = function () { return swingPins(first).map(function (p) { p.node = first.id; return p; }); };
    }
    if (nodes[0]) { follows(app, pb, nodes[0]); masksSection(app, pb, nodes[0]); }
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
      pb.appendChild(ui.lab('Its shapes', 'setup'));
      pb.appendChild(h('div', { class: 'thumbs' }, kids.map(function (k) { return th(k, k.shape || k.name, forced === k, false, function () { app.force[m.id] = k.id; app.test.mouth = null; app.refreshPanel(true); }); })));
      if (forced && !mir) pb.appendChild(h('div', { class: 'sl' }, [h('label', { text: 'This is the sound' }), h('select', { 'aria-label': 'Sound', onchange: function (e) { var v = e.target.value; app.edit(function () { forced.shape = v; }); } }, AL.SHAPES.map(function (s) { return h('option', { value: s, text: s, selected: forced.shape === s }); })), h('span')]));
      pb.appendChild(ui.lab('Try a sound', 'setup motion'));
      pb.appendChild(ui.row(kids.filter(function (k) { return k.shape; }).map(function (k) { return ui.chip(k.shape, { on: app.test.mouth != null && app.test.vis === k.shape && !app.force[m.id], fn: function () { delete app.force[m.id]; app.test.mouth = 1; app.test.vis = k.shape; app.refreshPanel(true); } }); })));
      return;
    }
    var art = isSet ? (AL.node(ch, m.pick.rest) || kids[0]) : m, mode = art.hinge ? 'hinge' : art.squash ? 'squash' : 'swap', cfg = art.hinge || art.squash;
    head(app, 'Mouth', mode === 'hinge' ? 'on a hinge' : mode === 'squash' ? 'squashes' : 'swaps', mode === 'hinge' ? 'One drawing that swings shut on a hinge as the voice goes quiet, tucking under the nose.' : mode === 'squash' ? 'One drawing that flattens shut as the voice goes quiet.' : 'Stays as drawn. Other mouths swap in from their buttons.');
    app.coach(mode === 'hinge' ? '<b>Mouth.</b> Drag the <b>Hinge</b> pin to the corner of the mouth, then slide <b>Try: talk</b>.' : '<b>Mouth.</b> Slide <b>Try: talk</b> to see it move.');
    if (mir) return;
    function setMode(v) { app.edit(function () { var keep = cfg ? cfg.rest : 'open'; delete art.hinge; delete art.squash; if (v === 'hinge') { AL.guessMouth(ch, art, cv.node); if (!art.hinge) { delete art.squash; art.hinge = { rest: keep, angle: 30 }; } art.hinge.rest = keep; } else if (v === 'squash') { var b = AL._px.boxOf(art); art.squash = { rest: keep, min: 0.14 }; art.pivot = { x: b.cx, y: b.y0 + b.h * 0.15 }; } }); }
    pb.appendChild(ui.lab('How it talks', 'setup motion'));
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
      pb.appendChild(ui.lab('Other mouths (each gets a button in Perform)', 'setup'));
      pb.appendChild(h('div', { class: 'thumbs' }, [th(art, 'talking', !forced || forced === art, true, function () { delete app.force[m.id]; app.refreshPanel(true); })].concat(others.map(function (k) { return th(k, k.name, forced === k, false, function () { app.force[m.id] = k.id; app.test.mouth = null; app.refreshPanel(true); }, 'Show “' + k.name + '” so it can be placed'); }))));
    }
    masksSection(app, pb, m);
    app.pinProvider = function () {
      if (forced && forced !== art) return [];
      return art.pivot && (art.hinge || art.squash) ? [{ key: 'hinge', label: art.hinge ? 'Hinge' : 'Shuts toward', x: art.pivot.x, y: art.pivot.y, node: art.id, color: '#fbbf24', set: function (x, y) { art.pivot.x = x; art.pivot.y = y; } }] : [];
    };
  }

  function armCard(app, pb, zone, own) {
    var ui = app.ui, ch = app.ch, set = own || app.zoneNode(zone), ZN = own && !(own.role && ZL2[own.role]) ? own.name : ZLABEL[zone], kids = (set.kids || []).filter(function (k) { return k.type === 'art'; });
    var cur = AL.node(ch, app.force[set.id]); if (!cur || AL.parentOf(ch, cur.id) !== set) cur = AL.node(ch, set.pick && set.pick.rest) || kids[0];
    app.force[set.id] = cur.id;
    var many = kids.length > 1, idx = kids.indexOf(cur), leg = own ? own.pick.hand.charAt(0) === 'F' : /^leg/.test(zone), PN = leg ? ['Hip', 'Knee', 'Foot'] : ['Shoulder', 'Elbow', 'Hand'];
    head(app, ZN, many ? kids.length + ' drawings' : 'bends', many ? 'Shows the drawing whose ' + PN[2].toLowerCase() + ' is nearest to where you want it, then bends it the rest of the way.' : (leg ? 'Bends from the hip and knee, so the foot can stay planted while the body moves, and step when it walks.' : 'Bends from the shoulder and elbow so the hand lands where you drag it.'));
    app.coach('<b>' + ZN + '.</b> Three pins' + (many ? ' per drawing' : '') + ': <b>' + PN.join(', ').toLowerCase() + '</b>. They were guessed from the art; drag any that are off. The small round arrow turns the ' + PN[1].toLowerCase() + ' over.');
    if (AL.stages.is(app, 'motion')) {
      /* MOTION: which of its drawings is on show this instant, and a way to mend that very one */
      pb.appendChild(ui.lab('Showing now', 'motion'));
      var nowStrip = h('div', { class: 'thumbs' }, kids.map(function (k, i) { return th(k, (i + 1) + '. ' + k.name, false, set.pick && set.pick.rest === k.id, function () { }, 'Drawing ' + (i + 1)); })), nowId = null;
      pb.appendChild(nowStrip);
      (app.padDraws = app.padDraws || []).push(function () { var id = app.out && app.out.picks ? app.out.picks[set.id] : null; if (id === nowId) return; nowId = id; kids.forEach(function (k, i) { var el = nowStrip.children[i]; if (!el) return; el.classList.toggle('on', k.id === id); if (k.id === id) nowStrip.scrollLeft = Math.max(0, el.offsetLeft - 70); }); });
      pb.appendChild(ui.row([
        ui.chip('Flip ' + PN[1].toLowerCase(), { title: 'Bend the drawing that is showing right now the other way', fn: function () { var k = AL.node(ch, nowId); if (k && k.limb) app.edit(function () { k.limb.flip = !k.limb.flip; }, false, 'Flip ' + PN[1].toLowerCase() + ' of ' + k.name); } }),
        ui.chip('Mend its pins', { icon: 'pin', title: 'Go to Set up with this very drawing open', fn: function () { if (nowId) app.force[set.id] = nowId; AL.stages.go(app, 'setup'); } })
      ]));
      pb.appendChild(ui.note('The cyan one is the drawing in use this instant. Move the character until the fault shows, then mend that drawing.'));
    }
    pb.appendChild(ui.lab('Its drawings', 'setup fit'));
    pb.appendChild(h('div', { class: 'thumbs' }, kids.map(function (k, i) { return th(k, (i + 1) + '. ' + k.name, k === cur, set.pick && set.pick.rest === k.id, function () { app.force[set.id] = k.id; app.refreshPanel(true); }, 'Drawing ' + (i + 1) + (set.pick.rest === k.id ? ' (the resting pose)' : '')); })));
    var shared = app.sharedShoulder !== false;
    pb.appendChild(ui.row([
      many ? ui.chip('‹', { title: 'Previous drawing', fn: function () { app.force[set.id] = kids[(idx + kids.length - 1) % kids.length].id; app.refreshPanel(true); } }) : null,
      many ? ui.chip('›', { title: 'Next drawing', fn: function () { app.force[set.id] = kids[(idx + 1) % kids.length].id; app.refreshPanel(true); } }) : null,
      ui.chip('Resting pose', { on: set.pick.rest === cur.id, icon: 'star', title: 'Use this drawing when the arm is left alone', fn: function () { app.edit(function () { set.pick.rest = cur.id; }); } }),
      ui.chip('Flip ' + PN[1].toLowerCase(), { on: !!(cur.limb && cur.limb.flip), title: 'Bend this drawing the other way', fn: function () { app.edit(function () { if (cur.limb) cur.limb.flip = !cur.limb.flip; }); } }),
      ui.chip('Guess pins again', { icon: 'wand', title: 'Work out every pin of this arm from the art again', fn: function () { app.edit(function () { var tz = app.zoneNode('body'), b = tz ? AL._px.boxOf(tz) : null; AL.guessFan(ch, set, b ? { x: b.cx, y: b.cy } : null); }); app.toast('Pins guessed from the art'); } })
    ]));
    var torsoN = app.zoneNode('body'), zmode = cur.zSplit ? 'half' : cur.zUnder ? 'behind' : 'front';
    pb.appendChild(ui.row([ui.chip('Try this drawing', { icon: 'play', cls: 'go', title: 'Bring it to life with ONLY this drawing, so you can drag the ' + PN[2].toLowerCase() + ' around and see how it bends', fn: function () { app.lockForce = {}; app.lockForce[set.id] = cur.id; app.setLive(true); } })]));
    if (torsoN) {
      pb.appendChild(ui.lab('This drawing and the body', 'fit'));
      pb.appendChild(ui.row([ui.seg([['front', 'In front', 'The whole drawing is in front of the body'], ['behind', 'Behind', 'The whole drawing passes behind the body'], ['half', 'Half and half', 'It comes round the body: one end behind it, the other end in front']], zmode, function (v) {
        app.liftArt = false;
        app.edit(function () { var at = cur.zSplit ? cur.zSplit.at : 0.5, end = cur.zSplit ? cur.zSplit.behind : 'root', tgt = (cur.zSplit && cur.zSplit.under) || cur.zUnder || cur.zWas || torsoN.id; delete cur.zSplit; if (cur.zUnder) cur.zWas = cur.zUnder; delete cur.zUnder; if (v === 'behind') cur.zUnder = tgt; else if (v === 'half') cur.zSplit = { at: at, behind: end, under: tgt }; });
      }, 'Where this drawing sits')]));
      if (cur.zSplit) {
        pb.appendChild(h('div', { class: 'sl' }, [h('label', { text: 'Behind is the' }), ui.seg([['root', PN[0], 'The ' + PN[0].toLowerCase() + ' end is behind the body, the ' + PN[2].toLowerCase() + ' in front'], ['tip', PN[2], 'The ' + PN[2].toLowerCase() + ' end is behind the body, the ' + PN[0].toLowerCase() + ' in front']], cur.zSplit.behind === 'tip' ? 'tip' : 'root', function (v) { app.edit(function () { cur.zSplit.behind = v; }); }, 'Which end is behind'), h('span')]));
        pb.appendChild(ui.slider('Changes over at', { min: 10, max: 90, value: Math.round((cur.zSplit.at == null ? 0.5 : cur.zSplit.at) * 100), set: function (v) { cur.zSplit.at = v / 100; app.liftArt = false; }, fmt: function (v) { return v < 40 ? 'near ' + PN[0].toLowerCase() : v > 60 ? 'near ' + PN[2].toLowerCase() : PN[1].toLowerCase(); }, title: 'Where along it the drawing goes from behind the body to in front' }));
        pb.appendChild(ui.note('Put the change-over where the ' + (leg ? 'leg' : 'arm') + ' is <b>clear of the body</b> (usually the ' + PN[1].toLowerCase() + ') and it cannot be seen.'));
      }
      if (zmode !== 'front') pb.appendChild(ui.toggle('Lift it to the front while I edit', app.liftArt !== false, function (v) { app.liftArt = v; app.refreshPanel(true); }, 'On: the whole drawing shows, to place pins. Off: it shows as it will really sit.'));
    }
    AL.stack.section(app, pb, set, { quiet: true });
    pb.appendChild(ui.lab('How it bends', 'motion'));
    var hose = !!(cur.limb && cur.limb.style === 'hose'), LIMB = leg ? 'leg' : 'arm', looped = !!set.pick.loop;
    pb.appendChild(h('div', { class: 'sl' }, [h('label', { text: 'Bends' }), ui.seg([['joint', 'At the ' + PN[1].toLowerCase(), 'Two stiff halves that fold at the ' + PN[1].toLowerCase()], ['hose', 'Like a hose', 'No ' + PN[1].toLowerCase() + ': it bows along its whole length, and can stretch. Old-cartoon arms, ropes, tentacles, a beam between two points']], hose ? 'hose' : 'joint', function (v) {
      app.edit(function () { kids.forEach(function (k) { if (!k.limb) return; if (v === 'hose') { k.limb.style = 'hose'; if (k.limb.stretch == null || k.limb.stretch < 0.5) k.limb.stretch = 0.6; } else delete k.limb.style; }); });
    }, 'How the ' + LIMB + ' bends'), h('span')]));
    if (many) pb.appendChild(ui.toggle('These drawings are frames of a loop', looped, function (v) { app.edit(function () { if (v) { set.pick.loop = 12; } else delete set.pick.loop; }); }, 'On: one ' + LIMB + ' that plays through its drawings as it moves (a glowing or flickering ' + LIMB + '). Off: each drawing is the ' + LIMB + ' at a different angle'));
    if (looped) pb.appendChild(ui.slider('Loop speed', { min: 1, max: 30, value: set.pick.loop, set: function (v) { set.pick.loop = v; }, fmt: function (v) { return v + ' a second'; } }));
    if (many && !looped) pb.appendChild(ui.toggle('Swap drawings', set.pick.swap !== false, function (v) { app.edit(function () { set.pick.swap = v; }); }, 'Off: always the resting drawing, bent'));
    pb.appendChild(ui.toggle('Bend to reach', !kids.some(function (k) { return k.limb && k.limb.bend === false; }), function (v) { app.edit(function () { kids.forEach(function (k) { if (k.limb) k.limb.bend = v; }); }); }, 'Off: the drawing only swings from the shoulder'));
    pb.appendChild(ui.toggle('One ' + PN[0].toLowerCase() + ' for all', shared, function (v) { app.sharedShoulder = v; app.refreshPanel(true); }, 'Moving the ' + PN[0].toLowerCase() + ' pin moves it on every drawing'));
    if (many && !looped) pb.appendChild(ui.slider('Cross-fade', { min: 0, max: 300, step: 10, value: set.pick.fade == null ? 110 : set.pick.fade, set: function (v) { set.pick.fade = v; }, fmt: function (v) { return v ? v + ' ms' : 'cut'; } }));
    pb.appendChild(ui.slider('Stretch', { min: 0, max: hose ? 300 : 40, value: Math.round((cur.limb && cur.limb.stretch != null ? cur.limb.stretch : 0.12) * 100), set: function (v) { kids.forEach(function (k) { if (k.limb) k.limb.stretch = v / 100; }); }, fmt: function (v) { return v + '%'; }, title: 'How far past its drawn length the arm may stretch to reach' }));
    if (hose) {
      pb.appendChild(ui.slider('When the hand is close', { min: 0, max: 100, value: Math.round((cur.limb.give || 0) * 100), set: function (v) { kids.forEach(function (k) { if (k.limb) k.limb.give = v / 100; }); }, fmt: function (v) { return v < 34 ? 'it bows' : v > 66 ? 'it shrinks' : 'a bit of both'; }, title: 'A hose keeps its length and bows out. A beam of light just gets shorter.' }));
      pb.appendChild(ui.slider('Always arcs', { min: 0, max: 40, value: Math.round((cur.limb.bow || 0) * 100), set: function (v) { kids.forEach(function (k) { if (k.limb) k.limb.bow = v / 100; }); }, fmt: function (v) { return v ? v + '%' : 'straight'; }, title: 'A curve it keeps even at full reach' }));
      pb.appendChild(ui.slider('Wobble', { min: 0, max: 24, value: cur.limb.wave || 0, set: function (v) { kids.forEach(function (k) { if (k.limb) k.limb.wave = v; }); }, fmt: function (v) { return v ? v + ' px' : 'none'; }, title: 'A wave that runs along it: a wiggly noodle, a crackling arc' }));
      pb.appendChild(ui.slider('Thickness', { min: 30, max: 400, step: 5, value: Math.round((cur.limb.thick || 1) * 100), set: function (v) { kids.forEach(function (k) { if (k.limb) k.limb.thick = v / 100; }); }, fmt: function (v) { return v + '%'; }, title: 'Make the whole limb fatter or thinner without redrawing it' }));
    }
    pb.appendChild(ui.toggle('Choose where the ' + PN[2].toLowerCase() + ' rests', !!set.pick.restAt, function (v) { app.edit(function () { if (v) { var rk = AL.node(ch, set.pick.rest) || cur; set.pick.restAt = { x: rk.limb.c.x, y: rk.limb.c.y + (hose ? 40 : 0) }; } else delete set.pick.restAt; }); }, 'Off: it rests where it was drawn. On: drag the amber pin to where it should hang when left alone. It shows when you Try it.'));
    if (leg && cur.limb && !hose) pb.appendChild(ui.toggle('Foot bar (heel to toe)', !!cur.limb.foot, function (v) { app.edit(function () { kids.forEach(function (k) { if (!k.limb) return; if (v) k.limb.foot = AL.guessFoot(ch, k); else k.limb.foot = null; }); }); }, 'Tells it where the sole is. The foot then stays level instead of tipping with the shin, and rolls from heel to toe as it walks. Drag the two green pins to the heel and the toe.'));
    limbWalk(app, pb, set, leg);
    var riders = AL.findAll(ch.root, function (q) { return q.ride && q.ride.of === set.id; });
    pb.appendChild(ui.lab(leg ? 'On its foot' : 'In its hand', 'setup'));
    pb.appendChild(ui.row(riders.map(function (q) { return ui.chip(q.name, { icon: 'pin', title: 'Open “' + q.name + '”', fn: function () { app.sel = { zone: AL.zoneOf(ch, q.id), feature: null, node: q.id }; app.resolveSel(); app.refreshPanel(); } }); }).concat([ui.chip(leg ? 'Add a foot or shoe' : 'Add a hand, or a thing to hold', { icon: 'plus', title: 'A separate drawing carried by the end of this ' + LIMB + ', turning with it. Pick several drawings and it can swap between them.', fn: function () { AL.intake.addRider(app, set); } })])));
    if (!hose) pb.appendChild(ui.slider('Elbow softness', { min: 5, max: 60, value: Math.round((cur.limb && cur.limb.soft != null ? cur.limb.soft : 0.3) * 100), set: function (v) { kids.forEach(function (k) { if (k.limb) k.limb.soft = v / 100; }); }, fmt: function (v) { return v + '%'; }, title: 'A soft elbow curves like a noodle; a hard one creases' }));
    app.pinProvider = function () {
      var l = cur.limb; if (!l) return [];
      return [
        { key: 'a', label: PN[0], x: l.a.x, y: l.a.y, node: cur.id, to: 'b', set: function (x, y) { var dx = x - l.a.x, dy = y - l.a.y; (app.sharedShoulder !== false ? kids : [cur]).forEach(function (k) { if (k.limb) { k.limb.a.x += dx; k.limb.a.y += dy; } }); } },
        { key: 'b', label: PN[1], x: l.b.x, y: l.b.y, node: cur.id, to: 'c', set: function (x, y) { l.b.x = x; l.b.y = y; } },
        { key: 'c', label: PN[2], x: l.c.x, y: l.c.y, node: cur.id, set: function (x, y) { l.c.x = x; l.c.y = y; } },
        l.foot ? { key: 'heel', label: 'Heel', x: l.foot.heel.x, y: l.foot.heel.y, node: cur.id, color: '#34d399', set: function (x, y) { l.foot.heel.x = x; l.foot.heel.y = y; } } : null,
        l.foot ? { key: 'toe', label: 'Toe', x: l.foot.toe.x, y: l.foot.toe.y, node: cur.id, color: '#34d399', set: function (x, y) { l.foot.toe.x = x; l.foot.toe.y = y; } } : null,
        set.pick.restAt ? { key: 'rest', label: 'Rests here', x: set.pick.restAt.x, y: set.pick.restAt.y, node: set.id, color: '#fbbf24', set: function (x, y) { set.pick.restAt.x = x; set.pick.restAt.y = y; } } : null,
        { key: 'flip', tap: function () { app.edit(function () { l.flip = !l.flip; }, false, 'Flip ' + PN[1].toLowerCase()); app.toast(PN[1] + ' turned over. Tap Try this drawing to see it bend.'); }, on: !!l.flip, x: l.b.x, y: l.b.y, node: cur.id, ox: -6, oy: -28 }
      ].filter(Boolean);
    };
  }

  /**
   * WALKING, on a limb's own card. A leg always walks; it can be copied (a second pair, a centipede's row), slid along the body, and
   * given its place in the round of steps. An arm can be told it may become a foot.
   */
  function limbWalk(app, pb, set, leg) {
    var ui = app.ui, ch = app.ch, isRole = set.role === 'legL' || set.role === 'legR', walks = AL.walksOn(set), side = /R$/.test(set.role || '') ? 0.5 : 0;
    function wk() { return set.pick.walk || (set.pick.walk = leg ? {} : { lift: true }); }
    pb.appendChild(ui.lab('Walking', 'motion'));
    if (!leg) {
      var pair = (set.role === 'armL' || set.role === 'armR') ? [AL.firstRole(ch.root, 'armL'), AL.firstRole(ch.root, 'armR')].filter(function (a) { return a && a.type === 'set' && a.pick && a.pick.by === 'reach'; }) : [set];
      pb.appendChild(ui.toggle(pair.length > 1 ? 'These arms can become feet' : 'This arm can become a foot', walks, function (v) { app.edit(function () { pair.forEach(function (a) { if (v) { a.pick.walk = a.pick.walk || { lift: true }; delete a.pick.walk.on; } else delete a.pick.walk; }); }, false, 'Arms can become feet'); }, 'When the ground comes within reach of the hand (the body tipped onto all fours, say) the hand plants and steps like a foot. Lifted out of reach, it is a hand again. Nothing else to set.'));
    } else if (!isRole) pb.appendChild(ui.toggle('This leg walks', walks, function (v) { app.edit(function () { wk().on = v; }, false, 'A leg walks'); }, 'Off: it only hangs where it was drawn'));
    if (walks) {
      var ph = set.pick.walk && set.pick.walk.phase != null ? set.pick.walk.phase : side + (leg ? 0 : 0.25);
      pb.appendChild(ui.slider('Step timing', { min: 0, max: 95, step: 5, value: Math.round(ph * 100), set: function (v) { wk().phase = v / 100; }, fmt: function (v) { return v ? v + '% round' : 'first'; }, title: 'Its place in the round of steps. Feet with the same timing step together; the others take turns in order. Two legs: 0 and 50. Four: 0, 25, 50, 75. A long row: spread them out and they move as a wave.' }));
      if (leg) pb.appendChild(ui.toggle('Lets go when lifted', !!(set.pick.walk && set.pick.walk.lift), function (v) { app.edit(function () { wk().lift = v; }, false, 'A leg lets go'); }, 'On: lifted out of reach of the ground this leg hangs free, and plants again when it can reach. For the front legs of something that rears up.'));
      else pb.appendChild(ui.slider('Wrist above the ground', { min: 0, max: 60, value: Math.round(set.pick.walk && set.pick.walk.palm != null ? set.pick.walk.palm : AL.palmGuess(ch, AL.node(ch, (set.pick.walk && set.pick.walk.kid) || set.pick.rest) || set.kids[0])), set: function (v) { wk().palm = v; }, fmt: function (v) { return v + ' px'; }, title: 'How high the wrist pin stays when the hand is planted, so the palm or knuckles rest on the ground and do not sink into it' }));
    }
    pb.appendChild(ui.lab('This ' + (leg ? 'leg' : 'arm'), 'setup'));
    var row = [];
    row.push(ui.chip('Copy this ' + (leg ? 'leg' : 'arm'), { icon: 'plus', title: leg ? 'Another leg just like this one, a little along the body, with the next step timing. Copy it again for as many as you like.' : 'Another arm just like this one, a little along the body', fn: function () { cards.copyLimb(app, set); } }));
    row.push(ui.chip('Move the whole ' + (leg ? 'leg' : 'arm'), { on: !!app.moveArt, icon: 'move', title: 'On: drag this limb on the picture to slide it, pins and all, to where it joins the body', fn: function () { app.moveArt = !app.moveArt; app.refreshPanel(true); } }));
    if (!set.role || !ZL2[set.role]) row.push(ui.chip('Remove', { cls: 'quiet', icon: 'trash', title: 'Take this limb away. Undo brings it back.', fn: function () { app.edit(function () { AL.remove(ch, set.id); app.sel.node = null; }, false, 'Remove a limb'); } }));
    pb.appendChild(ui.row(row));
  }
  /** A full copy of a limb (every drawing, pin and setting), slid a little along the body, walking on its own key with the next timing. */
  cards.copyLimb = function (app, set) {
    var ch = app.ch, made = null, leg = set.pick.hand.charAt(0) === 'F';
    app.edit(function () {
      var txt = JSON.stringify(set), ids = []; AL.walk(set, function (q) { ids.push(q.id); });
      ids.forEach(function (old) { txt = txt.split('"' + old + '"').join('"' + AL.id('n') + '"'); });
      var c2 = JSON.parse(txt), used = {}, k = 2; AL.walk(ch.root, function (q) { if (q.pick && q.pick.hand) used[q.pick.hand] = 1; });
      while (used[(leg ? 'F' : 'H') + k]) k++;
      var src = set.pick.walk || {}, ph = src.phase != null ? src.phase : (/R$/.test(set.role || '') ? 0.5 : 0) + (leg ? 0 : 0.25);
      c2.pick.hand = (leg ? 'F' : 'H') + k; delete c2.role; c2.name = String(set.name).replace(/ copy( \d+)?$/, '') + ' copy' + (k > 2 ? ' ' + (k - 1) : '');
      if (leg || set.pick.walk) c2.pick.walk = { phase: Math.round(((ph + 0.25) % 1) * 100) / 100 }; if (src.lift || !leg) { if (c2.pick.walk) c2.pick.walk.lift = true; }
      var par = AL.parentOf(ch, set.id), at = par.kids.indexOf(set); par.kids.splice(at + 1, 0, c2);
      app.nudge(c2, 30, 0); made = c2;
    }, true, 'Copy a limb');
    if (made) { app.sel = { zone: AL.zoneOf(ch, made.id), feature: null, node: made.id }; app.resolveSel(); app.moveArt = true; app.userView = false; app.focusSel(); app.refreshPanel(); app.toast('Copied. Drag it to where it joins the body.'); }
    return made;
  };
  /** Every limb the character has, to open by sight: two arms and two legs, or forty. */
  function limbsRow(app, pb) {
    var ch = app.ch, list = AL.findAll(ch.root, function (q) { return q.type === 'set' && q.pick && q.pick.by === 'reach' && q.pick.hand; }); if (list.length < 1) return;
    pb.appendChild(app.ui.lab('Limbs'));
    pb.appendChild(app.ui.row(list.map(function (q) { var role = q.role && ZL2[q.role] ? ZLABEL[q.role] : q.name; return partChip(q, role + (AL.walksOn(q) ? (q.pick.hand.charAt(0) === 'F' ? '' : ' · may walk') : ''), 'Open ' + role, function () { if (q.role && ZL2[q.role]) { app.tab = 'parts'; app.select(q.role); } else { app.sel = { zone: AL.zoneOf(ch, q.id), feature: null, node: q.id }; app.resolveSel(); app.userView = false; app.focusSel(); app.refreshPanel(); } }); })));
  }
  /** THE BODY'S OWN TURN DRAWINGS: a front view, the other side. It turns through them with the head, and passes through them when the whole character turns round. */
  function bodyAngles(app, pb, t) {
    var ui = app.ui, ch = app.ch;
    pb.appendChild(ui.lab('Its own turn drawings', 'setup'));
    if (t.type !== 'set') {
      pb.appendChild(ui.note('One drawing of the body for now. Give it its own angles (a front view, the other side) and it turns through them as the head turns, and passes through them when the whole character turns round, instead of only flipping.'));
      if (t.type === 'art') pb.appendChild(ui.row([ui.chip('Give the body its own angles', { icon: 'plus', title: 'The drawing you have becomes the first angle. Then add more, or copy this one and reshape it.', fn: function () {
        app.edit(function () {
          var fx = ch.settings.facing === 'left' ? -0.6 : ch.settings.facing === 'right' ? 0.6 : 0, art = { id: AL.id('n'), name: t.name, type: 'art', img: t.img, x: t.x, y: t.y, at: [fx, 0] };
          ['breathe', 'mesh', 'shapes', 'opacity'].forEach(function (k) { if (t[k] != null) { art[k] = t[k]; delete t[k]; } });
          delete t.img; delete t.x; delete t.y; t.type = 'set'; t.kids = [art]; t.pick = { by: 'turn', depth: 0, rest: art.id, fade: 90, gain: 1 };
        }, false, 'Body angles');
      } })]));
      return;
    }
    var kid = AL.node(ch, app.out && app.out.picks[t.id]) || t.kids[0];
    pb.appendChild(h('div', { class: 'padrow' }, [cards.turnPad(app, { set: t.id, moveDots: function () { return app.moveDots; } }), h('div', { class: 'side' }, [
      h('div', { class: 'rowf' }, [h('span', { class: 'badge', text: kid ? kid.name : '' })]),
      ui.toggle('Move the dots', !!app.moveDots, function (v) { app.moveDots = v; app.refreshPanel(true); }, 'Drag a dot to change when its drawing shows', 'When ON, dragging a dot in the pad moves it'),
      mirrorChip(app, t)
    ])]));
    pb.appendChild(ui.row([ui.chip('Add a drawing…', { icon: 'plus', cls: t.kids.length < 2 ? 'go' : '', title: 'Choose a picture of the body from another angle. It gets its own dot on the pad.', fn: function () { AL.intake.addAngle(app, t); } })]));
    pb.appendChild(ui.slider('Follows the head', { min: 0, max: 150, step: 5, value: Math.round((t.pick.gain == null ? 1 : t.pick.gain) * 100), set: function (v) { t.pick.gain = v / 100; }, fmt: function (v) { return v + '%'; }, title: 'How far the body turns when the head turns all the way. Less than 100%: the head leads and the body follows a little.' }));
    if (kid && kid.at) angleTools(app, pb, t, { kid: kid });
  }
  function bodyCard(app, pb) {
    var ui = app.ui, st = app.ch.settings, t = app.zoneNode('body'), tb = t.type === 'set' ? (AL.node(app.ch, app.out && app.out.picks[t.id]) || t.kids[0] || t) : t;
    head(app, 'Body', null, 'The chest breathes and the body leans. Head, arms and tail ride on it.');
    app.coach('<b>Body.</b> The ring is where the chest swells. The <b>Hips</b> pin is what it leans from.');
    AL.stance.section(app, pb);
    AL.tone.section(app, pb);
    pb.appendChild(ui.lab('Breathing', 'motion'));
    pb.appendChild(ui.toggle('Breathing', st.breathe !== false, function (v) { app.edit(function () { st.breathe = v; }); }, 'A slow swell of the chest; head and arms ride it'));
    if (tb.breathe) pb.appendChild(ui.slider('How deep', { min: 0, max: 3, step: 0.1, value: tb.breathe.amt == null ? 1 : tb.breathe.amt, set: function (v) { tb.breathe.amt = v; }, fmt: function (v) { return v + '×'; } }));
    pb.appendChild(ui.slider('Breaths a minute', { min: 6, max: 40, value: Math.round((st.breathRate || 0.28) * 60), set: function (v) { st.breathRate = v / 60; }, fmt: String }));
    pb.appendChild(ui.toggle('Never quite still', st.idle !== false, function (v) { app.edit(function () { st.idle = v; }); }, 'A slight drift of head and body when nothing else is happening'));
    var hung = AL.findAll(app.ch.root, function (n) { return n.swing && n !== t && AL.zoneOf(app.ch, n.id) === 'body'; });
    if (hung.length) { pb.appendChild(ui.lab('Swinging on the body', 'motion')); pb.appendChild(ui.row(hung.map(function (n) { return ui.chip(n.name, { icon: n.swing.kind === 'stiff' ? 'pin' : 'wand', title: 'Open \u201c' + n.name + '\u201d: how it swings', fn: function () { app.sel = { zone: 'body', feature: null, node: n.id }; app.resolveSel(); app.userView = false; app.focusSel(); app.refreshPanel(); } }); }))); }
    pb.appendChild(ui.lab('Which way it faces', 'setup'));
    pb.appendChild(h('div', { class: 'sl' }, [h('label', { text: 'Drawn facing' }), ui.seg([['left', 'Left'], ['front', 'Front'], ['right', 'Right']], st.facing || 'front', function (v) { app.edit(function () { st.facing = v; }); }, 'Which way the body was drawn'), h('span')]));
    pb.appendChild(ui.row([ui.chip('See it flipped', { on: !!app._bs && app._bs.flip, icon: 'flip', title: 'Show the mirrored side while building', fn: function () { app.buildSignals().flip = !app._bs.flip; app.refreshPanel(true); } })]));
    pb.appendChild(ui.lab('Turning around', 'motion'));
    pb.appendChild(ui.toggle('Turn around by itself', !!st.autoFlip, function (v) { app.edit(function () { st.autoFlip = v; if (v && st.facing === 'front') st.facing = 'left'; }); }, 'When the head turns the other way the whole character flips, so one three-quarter drawing faces both ways'));
    if (st.autoFlip) pb.appendChild(ui.slider('Flips past', { min: 0.1, max: 0.9, step: 0.05, value: st.flipAt == null ? 0.4 : st.flipAt, set: function (v) { st.flipAt = v; }, fmt: function (v) { return Math.round(v * 100) + '% turned'; } }));
    if (st.facing !== 'front') pb.appendChild(h('div', { class: 'sl' }, [h('label', { text: 'The turn is' }), ui.seg([['smooth', 'Smooth', 'The body tucks in, swaps side while tucked and opens out again; a tail or an arm is pulled in behind the body and grows back out. A head with both sides drawn just keeps turning.'], ['snap', 'Instant', 'Straight to the mirror image, no in-between'], ['turn', 'A card', 'It swings round like a cardboard cut-out']], st.flipStyle || 'smooth', function (v) { app.edit(function () { st.flipStyle = v; }); app.refreshPanel(true); }, 'How turning around looks'), h('span')]));
    if (st.facing !== 'front' && (st.flipStyle || 'smooth') === 'smooth') {
      pb.appendChild(ui.slider('It takes', { min: 0.04, max: 0.6, step: 0.02, value: st.flipTime == null ? 0.14 : st.flipTime, set: function (v) { st.flipTime = v; }, fmt: function (v) { return Math.round(v * 24) + ' frames'; }, title: 'How long the turn lasts, in frames of film (24 a second)' }));
      pb.appendChild(ui.slider('It tucks in', { min: 0, max: 0.9, step: 0.02, value: st.flipTuck == null ? 0.45 : st.flipTuck, set: function (v) { st.flipTuck = v; }, fmt: function (v) { return Math.round(v * 100) + '%'; }, title: 'How far the body, tail and arms pull in toward the middle before they swap sides. 0 swaps at full width.' }));
    }
    var can = AL.walkDefaults(app.ch), wk = st.walk;
    limbsRow(app, pb);
    pb.appendChild(ui.row([ui.chip('Ground and walking', { icon: 'fwd', cls: 'quiet', title: 'Everything about walking is on one card: the bar under the feet on the body map', fn: function () { app.tab = 'parts'; app.select('ground'); } })]));
    bodyAngles(app, pb, t);
    app.pinProvider = function () {
      var pins = [];
      if (wk && can && wk.on !== false && wk.ground !== 'plane') pins.push({ key: 'ground', label: 'Ground', x: st.axisX == null ? app.ch.w / 2 : st.axisX, y: wk.groundY, node: null, color: '#a78bfa', set: function (x, y) { wk.groundY = Math.round(y); } });
      if (tb.breathe) pins.push({ key: 'chest', label: 'Chest', x: tb.breathe.cx, y: tb.breathe.cy, node: null, ring: tb.breathe.r, set: function (x, y) { tb.breathe.cx = x; tb.breathe.cy = y; } });
      if (tb.breathe) pins.push({ key: 'size', label: 'Size', x: tb.breathe.cx + tb.breathe.r, y: tb.breathe.cy, node: null, color: '#a78bfa', set: function (x, y) { tb.breathe.r = Math.max(8, Math.hypot(x - tb.breathe.cx, y - tb.breathe.cy)); } });
      if (t.pivot) pins.push({ key: 'hips', label: 'Hips', x: t.pivot.x, y: t.pivot.y, node: null, color: '#fbbf24', set: function (x, y) { t.pivot.x = x; t.pivot.y = y; } });
      return pins;
    };
    if (app.fitOn && app.fitOn.box && AL.node(app.ch, app.fitOn.node)) { var fn0 = AL.node(app.ch, app.fitOn.node); app.pinProvider = function () { return AL.fit.pins(app, fn0); }; }
  }

  function nearBody(app) { var tz = app.zoneNode('body'), b = tz ? AL._px.boxOf(tz) : null; return b ? { x: b.cx, y: b.y0 + b.h / 2 } : null; }
  /** Everything about a part that swings: which kind, how springy, what the wind does to it. */
  function swingControls(app, pb, art) {
    var ui = app.ui, ch = app.ch, d = art.swing;
    if (!d) {
      pb.appendChild(ui.note('Make it move by itself when whatever it is fixed to moves.'));
      pb.appendChild(ui.row([
        ui.chip('Bendy', { cls: 'go', icon: 'wand', title: 'Hair, a braid, a feather, a tail, a ribbon: it bends along its length', fn: function () { app.edit(function () { AL.guessSwing(ch, art, nearBody(app), 'bendy'); }); } }),
        ui.chip('Stiff', { icon: 'wand', title: 'A chain link, an earring, a charm: it never bends, it swings and rattles', fn: function () { app.edit(function () { AL.guessSwing(ch, art, null, 'stiff'); }); } })
      ]));
      return;
    }
    var stiffK = d.kind === 'stiff';
    pb.appendChild(ui.row([ui.seg([['bendy', 'Bendy', 'Bends along its length: hair, braids, feathers, tails'], ['stiff', 'Stiff', 'Never bends; swings and rattles: chain links, earrings, charms']], d.kind || 'bendy', function (v) { app.edit(function () { d.kind = v; if (v === 'stiff' && d.hang == null) d.hang = true; }); }, 'What kind of thing is it?'),
      ui.chip(d.on === false ? 'Off' : 'On', { on: d.on !== false, title: 'Switch the swinging on or off', fn: function () { app.edit(function () { d.on = d.on === false; }); } })]));
    pb.appendChild(ui.slider('Springiness', { min: 0, max: 1, step: 0.02, value: d.stiff, set: function (v) { d.stiff = v; }, fmt: function (v) { return v < 0.2 ? 'floppy' : v > 0.7 ? 'snappy' : Math.round(v * 100) + ''; }, title: 'Low is floppy, high snaps back' }));
    pb.appendChild(ui.slider('Settle', { min: 0, max: 1, step: 0.02, value: d.damp, set: function (v) { d.damp = v; }, fmt: function (v) { return v < 0.15 ? 'keeps going' : v > 0.7 ? 'at once' : Math.round(v * 100) + ''; }, title: 'How quickly it comes to rest. Low keeps swinging (a rattling chain).' }));
    pb.appendChild(ui.slider('Follow-through', { min: 0, max: 2, step: 0.05, value: d.follow == null ? 1 : d.follow, set: function (v) { d.follow = v; }, fmt: function (v) { return v + '\u00d7'; }, title: '0 rides rigidly, 1 is left behind and catches up, more is flung' }));
    if (!stiffK) pb.appendChild(ui.slider('Joints', { min: 1, max: 8, value: d.segs || 4, set: function (v) { d.segs = v; }, fmt: String, title: 'More joints make a longer, lazier wave down its length' }));
    else pb.appendChild(ui.slider('Rattle room', { min: 5, max: 180, value: d.limit == null ? 80 : d.limit, set: function (v) { d.limit = v; }, fmt: function (v) { return '\u00b1' + v + '\u00b0'; }, title: 'How far it may swing before it knocks and bounces back' }));
    pb.appendChild(ui.toggle('Hangs down', !!d.hang, function (v) { app.edit(function () { d.hang = v; }); }, 'Keeps pointing the way it was drawn when what it hangs from tilts'));
    pb.appendChild(ui.slider('Breeze', { min: 0, max: 2.5, step: 0.05, value: d.wind == null ? 1 : d.wind, set: function (v) { d.wind = v; }, fmt: function (v) { return v < 0.03 ? 'ignores it' : v + '\u00d7'; }, title: 'How much the wind moves it' }));
    if (!stiffK) pb.appendChild(ui.slider('Flutter', { min: 0, max: 1, step: 0.02, value: d.flutter || 0, set: function (v) { d.flutter = v; }, fmt: function (v) { return Math.round(v * 100) + ''; }, title: 'A fast shiver in the wind: feathers, leaves, ribbons' }));
    pb.appendChild(ui.slider('Idle wag', { min: 0, max: 0.3, step: 0.01, value: d.wag || 0, set: function (v) { d.wag = v; }, fmt: function (v) { return Math.round(v * 100) + ''; }, title: 'A small movement of its own when nothing else is happening' }));
    /* chains: hang this from the tip of another swinging part */
    var hosts = AL.findAll(ch.root, function (n) { return n.swing && n !== art && n.attach !== art.id; }), host = art.attach ? AL.node(ch, art.attach) : null;
    if (hosts.length || host) pb.appendChild(h('div', { class: 'sl' }, [h('label', { text: 'Hangs from' }), h('select', { 'aria-label': 'Hangs from', onchange: function (e) { var v = e.target.value; app.edit(function () { if (v) art.attach = v; else delete art.attach; }); } }, [h('option', { value: host && !host.swing ? host.id : '', text: host && !host.swing ? host.name : 'the body it is on' })].concat(hosts.map(function (n) { return h('option', { value: n.id, text: n.name, selected: art.attach === n.id }); }))), h('span')]));
    pb.appendChild(ui.row([
      ui.chip('Guess pins again', { icon: 'wand', fn: function () { app.edit(function () { AL.guessSwing(ch, art, stiffK ? null : nearBody(app), d.kind); }); } }),
      ui.chip('Stop it swinging', { cls: 'quiet', icon: 'trash', fn: function () { app.edit(function () { delete art.swing; }); } })
    ]));
    pb.appendChild(ui.note('Tap <b>Try it</b>, then drag the body or the head. The <b>Breeze</b> slider is there too.'));
  }
  function swingPins(art) {
    var d = art.swing; if (!d || !art.pivot) return [];
    return [{ key: 'root', label: d.kind === 'stiff' ? 'Hangs from' : 'Root', x: art.pivot.x, y: art.pivot.y, node: null, to: 'tip', color: '#fbbf24', set: function (x, y) { art.pivot.x = x; art.pivot.y = y; } }, { key: 'tip', label: 'Tip', x: d.tip.x, y: d.tip.y, node: null, set: function (x, y) { d.tip.x = x; d.tip.y = y; } }];
  }
  function tailCard(app, pb) {
    var t = app.zoneNode('tail'), art = t.type === 'art' ? t : AL.find(t, function (k) { return k.type === 'art'; });
    head(app, 'Tail', art && art.swing ? 'swings' : 'in place', 'Swings by itself when the body moves, and settles again. Nothing to animate.');
    app.coach('<b>Tail.</b> <b>Root</b> is where it joins the body, <b>Tip</b> is the loose end.');
    if (!art) return;
    pb.appendChild(app.ui.lab('Swinging', 'motion'));
    swingControls(app, pb, art);
    app.pinProvider = function () { return swingPins(art); };
  }
  /* ── masks: show a part only inside another, or hide it where another is ── */
  function masksSection(app, pb, n) {
    var ui = app.ui, ch = app.ch;
    pb.appendChild(ui.lab('Masks', 'fit'));
    (n.clips || []).forEach(function (q, i) {
      var by = AL.node(ch, q.by); if (!by) return;
      pb.appendChild(h('div', { class: 'rowf', style: 'margin-top:4px' }, [h('span', { class: 'note', style: 'margin:0;flex:1;min-width:0', html: q.cut ? 'Hidden where <b>' + by.name + '</b> is' : 'Shows only inside <b>' + by.name + '</b>' }),
        ui.chip(by.maskOnly ? 'invisible' : 'drawn', { on: !by.maskOnly, title: 'Is \u201c' + by.name + '\u201d drawn as well, or only used as a mask?', fn: function () { app.edit(function () { if (by.maskOnly) delete by.maskOnly; else by.maskOnly = true; }); } }),
        h('button', { class: 'ib', title: 'Remove this mask', onclick: function () { app.edit(function () { n.clips.splice(i, 1); if (!n.clips.length) delete n.clips; }); } }, AL.icon('x'))]));
    });
    if (!(n.clips || []).length) pb.appendChild(ui.note('Keep this inside another shape (a pupil inside the white of an eye, teeth inside a mouth), or hide it where another shape is (an eyelid, a muzzle).'));
    function pick(cut) {
      var par = AL.parentOf(ch, n.id), gp = par ? AL.parentOf(ch, par.id) : null, seen = {}, list = [];
      [par, gp].forEach(function (g) { if (g) (g.kids || []).forEach(function (k) { if (k !== n && !seen[k.id] && k.type !== 'mirror' && !AL.find(k, function (q) { return q === n; })) { seen[k.id] = 1; list.push(k); } }); });
      var body = h('div', { class: 'menu' });
      list.forEach(function (k) { body.appendChild(h('button', { title: k.name, onclick: function () { app.closeSheet(); app.edit(function () { n.clips = (n.clips || []).concat([cut ? { by: k.id, cut: true } : { by: k.id }]); }); } }, [cards.mini(k, 30), h('span', { text: k.name })])); });
      if (!list.length) body.appendChild(ui.note('There is no other layer beside this one to use as a mask.'));
      app.sheet(cut ? 'Hide \u201c' + n.name + '\u201d where which layer is?' : 'Show \u201c' + n.name + '\u201d only inside which layer?', body, []);
    }
    pb.appendChild(ui.row([ui.chip('Show only inside\u2026', { icon: 'plus', title: 'Pick the shape this must stay inside', fn: function () { pick(false); } }), ui.chip('Hide where\u2026', { icon: 'plus', title: 'Pick the shape that hides this', fn: function () { pick(true); } })]));
  }
  function legCard(app, pb, zone) {
    var n = app.zoneNode(zone);
    if (n.type === 'set' && n.pick && n.pick.by === 'reach') return armCard(app, pb, zone);
    head(app, ZLABEL[zone], 'planted', 'Stays as drawn. Make it bend and the foot can stay planted while the body moves, and step when the character walks.');
    app.coach('<b>' + ZLABEL[zone] + '.</b> One piece for now. <b>Make it bend</b> to give it a hip, a knee and a foot.');
    if (n.type === 'art') pb.appendChild(app.ui.row([app.ui.chip('Make it bend', { cls: 'go', icon: 'wand', fn: function () { app.edit(function () { AL.applyRole(app.ch, n, zone); }); } })]));
    follows(app, pb, n);
  }

  /* ── walking: the same controls in Build (the Body card) and in Perform ── */
  cards.walkControls = function (app, pb, live) {
    var ui = app.ui, st = app.ch.settings, can = AL.walkDefaults(app.ch), wk = st.walk, wkr = app.inp.walker;
    if (!can) {
      var hv = st.hover;
      if (live) { if (wkr.hover()) pb.appendChild(ui.row([ui.chip('Back to the middle', { cls: 'quiet', title: 'Float back to where it started', fn: function () { wkr.home(); } })])); return; }
      pb.appendChild(ui.toggle('Floats where you drag the body', !!(hv && hv.on !== false), function (v) { app.edit(function () { st.hover = st.hover || { lean: 1, quick: 9 }; st.hover.on = v; }); }, 'For a character with no legs: a hovering robot, a ghost, a fish. It drifts after your finger and leans into the move; whatever hangs from it lags behind.'));
      if (hv && hv.on !== false) {
        pb.appendChild(ui.slider('Lean', { min: 0, max: 2.5, step: 0.05, value: hv.lean == null ? 1 : hv.lean, set: function (v) { hv.lean = v; }, fmt: function (v) { return v + '\u00d7'; }, title: 'How far it tips into the move' }));
        pb.appendChild(ui.slider('Keeps up', { min: 2, max: 24, step: 0.5, value: hv.quick == null ? 9 : hv.quick, set: function (v) { hv.quick = v; }, fmt: function (v) { return v < 5 ? 'drifts' : v > 15 ? 'snappy' : 'easy'; }, title: 'How quickly it catches up with your finger' }));
      } else pb.appendChild(ui.note('Or give it two legs that bend (tap a leg on the body map, then <b>Make it bend</b>) and it walks: the feet plant and step by themselves.'));
      return;
    }
    if (!wk) wk = st.walk = can;
    if (!live) pb.appendChild(ui.toggle('Walks when you drag the body', wk.on !== false, function (v) { app.edit(function () { wk.on = v; }); }, 'Each foot stays planted until it is left behind, then steps. Nothing slides.'));
    if (wk.on === false) return;
    if (live) {
      pb.appendChild(ui.row([
        ui.chip('Ground', { on: wkr.grounded(), icon: 'home', title: 'ON: there is a ground line where it stands now; pull it up to jump. OFF: it is always on the ground, so up and down the stage is walking too (stairs, a hill).', fn: function () { wkr.setGround(!wkr.grounded()); app.toast(wkr.grounded() ? 'Ground is here. Pull up to jump.' : 'No ground line: it walks wherever you take it.'); app.refreshPanel(true); } }),
        ui.chip('Walk settings', { on: !!app.walkOpen, title: 'Stride, body bob, heel and toe', fn: function () { app.walkOpen = !app.walkOpen; app.refreshPanel(true); } }),
        ui.chip('Back to the middle', { cls: 'quiet', title: 'Walk back to where it started', fn: function () { wkr.home(); } })
      ]));
      if (!app.walkOpen) return;
    } else pb.appendChild(h('div', { class: 'sl' }, [h('label', { text: 'It starts' }), ui.seg([['line', 'On a ground line', 'Pulling the body up takes the feet off the ground: a jump'], ['plane', 'Walking anywhere', 'Up and down the stage is walking too, and further up can be further away']], wk.ground || 'line', function (v) { app.edit(function () { wk.ground = v; }); wkr.free = null; }, 'How it starts. The Ground button in Perform switches this at any moment.'), h('span')]));
    var bars = !!(wkr.legs() && wkr.legs().L.bar && wkr.legs().R.bar);
    pb.appendChild(h('div', { class: 'sl' }, [h('label', { text: 'It moves by' }), ui.seg([['walk', 'Steps', 'One foot at a time'], ['hop', 'Hops', 'Both feet together, the body arcs']], wk.gait || 'walk', function (v) { app.edit(function () { wk.gait = v; }); }, 'The gait'), h('span')]));
    pb.appendChild(ui.slider('Stride', { min: 8, max: 220, value: wk.stride, set: function (v) { wk.stride = v; }, fmt: function (v) { return v + ' px'; }, title: 'How far apart the footfalls are' }));
    pb.appendChild(ui.slider('Step height', { min: 0, max: 90, value: wk.stepH, set: function (v) { wk.stepH = v; }, fmt: function (v) { return v + ' px'; }, title: 'How high a foot lifts' }));
    pb.appendChild(ui.slider('Rise and fall', { min: 0, max: 4, step: 0.05, value: wk.bob == null ? 0.8 : wk.bob, set: function (v) { wk.bob = v; }, fmt: function (v) { return v < 0.05 ? 'flat' : v < 1.2 ? 'natural' : v < 2.5 ? 'bouncy' : 'cartoony'; }, title: 'The wave the body and head ride with every step: low as the weight lands on a foot, high as the other leg passes. From flat to cartoony.' }));
    pb.appendChild(ui.slider('Step', { min: -1, max: 1, step: 0.05, value: wk.style == null ? -0.4 : wk.style, set: function (v) { wk.style = v; }, fmt: function (v) { return v < -0.12 ? 'heel first' : v > 0.12 ? 'tiptoe' : 'flat'; }, title: 'Where the weight lands: reaching out with the heel, flat-footed, or up on the toes' }));
    pb.appendChild(ui.slider('Heel to toe', { min: 0, max: 1.6, step: 0.05, value: wk.roll == null ? 1 : wk.roll, set: function (v) { wk.roll = v; }, fmt: function (v) { return v < 0.03 ? 'none' : v + '\u00d7'; }, title: 'How much the foot rolls. None suits a ball or a peg for a foot.' }));
    if (!bars) pb.appendChild(ui.note('The last two need to know where the sole is: open a <b>leg</b> and switch on its <b>Foot bar</b>.'));
    pb.appendChild(ui.slider('Lean', { min: 0, max: 1.5, step: 0.05, value: wk.lean == null ? 0.5 : wk.lean, set: function (v) { wk.lean = v; }, fmt: function (v) { return v + '\u00d7'; }, title: 'How far it leans into the walk' }));
    pb.appendChild(ui.slider('Arm swing', { min: 0, max: 50, value: wk.armSwing == null ? 18 : wk.armSwing, set: function (v) { wk.armSwing = v; }, fmt: function (v) { return v + '\u00b0'; }, title: 'A hand nobody is holding swings against the legs' }));
    pb.appendChild(h('div', { class: 'sl' }, [h('label', { text: 'In the air, it' }), ui.seg([['tuck', 'Jumps', 'The legs fold on the way up and swing forward on the way down, ready to land'], ['hang', 'Hangs', 'The legs dangle']], wk.air === 'hang' ? 'hang' : 'tuck', function (v) { app.edit(function () { wk.air = v; }); }, 'What the legs do off the ground'), h('span')]));
    pb.appendChild(ui.toggle('Falls back when let go', wk.fall !== false, function (v) { app.edit(function () { wk.fall = v; }); }, 'Off: it stays where you leave it, in the air'));
    if (wk.ground === 'plane') pb.appendChild(ui.slider('Smaller far away', { min: 0, max: 1, step: 0.05, value: wk.depth == null ? 0.35 : wk.depth, set: function (v) { wk.depth = v; }, fmt: function (v) { return Math.round(v * 100) + '%'; }, title: 'How much it shrinks as it walks up the stage' }));
    if (st.facing !== 'front') pb.appendChild(ui.toggle('Turns to face the way it walks', wk.face !== false, function (v) { app.edit(function () { wk.face = v; }); }));
    if (!live) pb.appendChild(ui.note('Tap <b>Try it</b> and drag the body. Pull it up past its tiptoes to jump. Drag a foot or a hand with another finger and it is yours until you let go.'));
  };

  /* ── shapes: pose a part with pins as many times as you like; whatever drives a shape blends it in ── */
  function ringPins(n) {
    var b = AL.boundsOf(n), cx = (b.x0 + b.x1) / 2, cy = (b.y0 + b.y1) / 2, rx = (b.x1 - b.x0) / 2 * 0.86, ry = (b.y1 - b.y0) / 2 * 0.86;
    n.mesh = n.mesh || { pins: [] }; n.mesh.pins = n.mesh.pins || []; n.mesh.quiet = true;
    ['Right', 'Lower right', 'Bottom', 'Lower left', 'Left', 'Upper left', 'Top', 'Upper right'].forEach(function (nm, i) { var a = i * Math.PI / 4; n.mesh.pins.push({ id: AL.id('p'), name: nm, x: Math.round(cx + Math.cos(a) * rx), y: Math.round(cy + Math.sin(a) * ry) }); });
    n.mesh.pins.push({ id: AL.id('p'), name: 'Middle', x: Math.round(cx), y: Math.round(cy) });
  }
  function shapeOn(app, n, sh) { var w = {}; w[n.id] = {}; w[n.id][sh.id] = 1; app.shapeEdit = { node: n.id, shape: sh.id, w: w }; }
  function shapesSection(app, pb, n) {
    var ui = app.ui, ch = app.ch, se = app.shapeEdit && app.shapeEdit.node === n.id ? app.shapeEdit : null, pins = n.mesh && n.mesh.pins ? n.mesh.pins : [];
    pb.appendChild(ui.lab('Shapes', 'setup'));
    if (!pins.length) {
      pb.appendChild(ui.note('Give this part a few pins and it can be pushed into other shapes: an eye squeezed shut, a cheek puffed out, a body squashed. Each shape is blended in smoothly by a button, the mouth, a blink or the head turning.'));
      pb.appendChild(ui.row([ui.chip('Add pins around it', { cls: 'go', icon: 'pin', title: 'Eight pins round its edge and one in the middle. Drag them to where the shape should bend from.', fn: function () { app.edit(function () { ringPins(n); }, false, 'Add shape pins'); } })]));
      return;
    }
    var SG = [['', 'nothing yet']].concat(SIGS, [['blink', 'a blink on its own side'], ['lidUp', 'the mood: relaxed'], ['lidLow', 'the mood: cheery'], ['spin', 'the whole character turning round'], ['always', 'all the time']], (ch.triggers || []).map(function (t) { return ['trig:' + t.id, 'the “' + t.name + '” button']; }), [['+new', 'a new button…']]);
    (n.shapes || []).forEach(function (sh) {
      var on = !!se && se.shape === sh.id;
      pb.appendChild(h('div', { class: 'rowf', style: 'margin-top:6px' }, [
        ui.chip(sh.name, { on: on, icon: 'pin', title: on ? 'Stop posing this shape' : 'Pose this shape: the pins jump to it, and dragging them changes it', fn: function () { if (on) app.shapeEdit = null; else shapeOn(app, n, sh); app.refreshPanel(true); } }),
        h('span', { class: 'note', style: 'margin:0', text: 'with' }),
        h('select', { 'aria-label': 'What blends this shape in', title: 'What blends this shape in', style: 'flex:1;min-width:0', onchange: function (ev) { var v = ev.target.value; if (v === '+new') { app.promptText('Name the button (it shows in Perform)', sh.name, function (nm) { var t = { id: AL.id('t'), name: nm }; app.edit(function () { ch.triggers.push(t); sh.sig = 'trig:' + t.id; }, false, 'A button for a shape'); }); return; } app.edit(function () { sh.sig = v; }, false, 'What a shape follows'); } }, SG.map(function (q) { return h('option', { value: q[0], text: q[1], selected: (sh.sig || '') === q[0] }); })),
        h('button', { class: 'ib', title: 'Remove this shape', onclick: function () { app.edit(function () { n.shapes = n.shapes.filter(function (q) { return q !== sh; }); if (!n.shapes.length) delete n.shapes; if (on) app.shapeEdit = null; }, false, 'Remove a shape'); } }, AL.icon('x'))
      ]));
      if (on) {
        pb.appendChild(ui.slider('How far', { min: -150, max: 150, step: 5, value: Math.round((sh.amt == null ? 1 : sh.amt) * 100), set: function (v) { sh.amt = v / 100; }, fmt: function (v) { return v + '%'; }, title: 'How far this shape goes when what drives it is fully on. Below zero it goes the other way.' }));
        pb.appendChild(ui.row([
          ui.chip('Bouncy', { on: !!sh.bounce, title: 'It overshoots and settles, like something soft', fn: function () { app.edit(function () { sh.bounce = !sh.bounce; }, false, 'Bouncy'); } }),
          ui.chip('Rename', { fn: function () { app.promptText('Name this shape', sh.name, function (v) { app.edit(function () { sh.name = v; }, false, 'Rename a shape'); }); } }),
          ui.chip('Start again', { cls: 'quiet', title: 'Put every pin back where it rests, for this shape only', fn: function () { app.edit(function () { sh.d = {}; }, false, 'Clear a shape'); } }),
          ui.chip('Done posing', { cls: 'go', fn: function () { app.shapeEdit = null; app.refreshPanel(true); } })
        ]));
        pb.appendChild(ui.note('The purple pins show <b>this shape</b>: drag them to pose it. <b>Done posing</b> goes back to the resting shape. In Perform the part melts from one shape into another.'));
      }
    });
    pb.appendChild(ui.row([
      ui.chip('Add a shape', { icon: 'plus', cls: (n.shapes || []).length ? '' : 'go', title: 'A new pose of this part, made by dragging its pins', fn: function () { app.promptText('Name this shape', 'Shape ' + ((n.shapes || []).length + 1), function (v) { var sh = { id: AL.id('s'), name: v, sig: '', amt: 1, d: {} }; app.edit(function () { (n.shapes = n.shapes || []).push(sh); }, true, 'Add a shape'); shapeOn(app, n, sh); app.refreshPanel(true); }); } }),
      ui.chip('Add a pin', { icon: 'pin', title: 'One more pin, in the middle: drag it where you need it', fn: function () { app.edit(function () { var b = AL.boundsOf(n); n.mesh.pins.push({ id: AL.id('p'), name: 'Pin ' + (n.mesh.pins.length + 1), x: Math.round((b.x0 + b.x1) / 2), y: Math.round((b.y0 + b.y1) / 2) }); }, false, 'Add a pin'); } }),
      ui.chip('Handles in Perform', { on: !n.mesh.quiet, title: 'On: each pin can be dragged while performing. Off: the pins only shape the part.', fn: function () { app.edit(function () { if (n.mesh.quiet) delete n.mesh.quiet; else n.mesh.quiet = true; }, false, 'Pins as handles'); } })
    ]));
  }
  function shapePins(app, n) {
    var se = app.shapeEdit, sh = (n.shapes || []).filter(function (q) { return q.id === se.shape; })[0]; if (!sh || !n.mesh) { app.shapeEdit = null; return null; }
    return function () { return n.mesh.pins.map(function (q) { var d = sh.d[q.id] || [0, 0]; return { key: q.id, label: q.name, x: q.x + d[0], y: q.y + d[1], node: n.id, color: '#a78bfa', set: function (x, y) { sh.d[q.id] = [Math.round((x - q.x) * 10) / 10, Math.round((y - q.y) * 10) / 10]; } }; }); };
  }

  /* ── a piece of a turning head: which pad position it is seen at, and whose landmarks bend it ── */
  function pieceSection(app, pb, n, set) {
    var ui = app.ui, ch = app.ch, B = AL.bridge, ang = AL.ancestorWith(ch, n.id, function (a) { return AL.parentOf(ch, a.id) === set; }), direct = !!ang && AL.parentOf(ch, n.id) === ang, k = B.key(n), side = h('div', { class: 'side' });
    pb.appendChild(ui.lab('When the head turns', 'fit'));
    if (direct) {
      var cur = B.warpsWith(set, k), own = Object.keys(set.warpWith || {}).filter(function (q) { return q !== k && set.warpWith[q] === 'own'; });
      side.appendChild(h('select', { 'aria-label': 'This piece warps with', title: 'A piece can bend with the whole head, with another piece, or by landmarks of its own', onchange: function (ev) { var v = ev.target.value; app.edit(function () { B.setWarpsWith(set, k, v); }, false, 'Warps with'); } },
        [['', 'Bends with the whole head'], ['own', 'Bends by its own landmarks']].concat(own.map(function (q) { return [q, 'Bends with “' + q + '”']; })).map(function (o) { return h('option', { value: o[0], text: o[1], selected: o[0] === cur }); })));
      B.neighbours(set, ang).forEach(function (o) { side.appendChild(ui.chip('Match to ' + o.name, { icon: 'wand', title: 'Mark landmarks on this piece only, between “' + ang.name + '” and “' + o.name + '”', fn: function () { B.open(app, set, ang, o, k); } })); });
    }
    pb.appendChild(h('div', { class: 'padrow' }, [cards.turnPad(app, { small: true, bridges: false, set: set.id }), side]));
    if (direct) pb.appendChild(ui.note('Tap a dot to see this piece at that angle. With landmarks of its own it warps on its own layer and slides over the rest of the head.'));
  }

  /* ── a round shape: more than a ball ── */
  function roundExtra(app, pb, host, ball) {
    var ui = app.ui, ch = app.ch, arts = AL.findAll(host, function (q) { return q.type === 'art'; }), shell = AL.node(ch, ball.shell);
    pb.appendChild(ui.toggle('Follow the drawing’s own outline', ball.kind === 'lathe', function (v) { app.edit(function () { if (v) { ball.kind = 'lathe'; if (!shell && arts.length) ball.shell = biggest(arts).id; } else delete ball.kind; }); }, 'An egg, a pear, a snout, a bottle: parts slide round the real outline of the drawing instead of round a ball'));
    if (ball.kind === 'lathe') pb.appendChild(ui.row([partChip(shell, 'Outline of: ' + (shell ? shell.name : 'choose a drawing'), 'Which drawing is the solid shape', function () { pickPart(app, 'Which drawing is the solid shape?', arts, null, function (q) { if (q) app.edit(function () { ball.shell = q.id; }, false, 'Outline'); }); })]));
    pb.appendChild(ui.toggle('It can be turned right round', !!ball.spin, function (v) { app.edit(function () { if (v) ball.spin = true; else delete ball.spin; }); }, 'Adds a “Turn right round” slider in Perform: to its side, to its back. Parts marked “on the back” come into view.'));
  }
  function biggest(arts) { var best = arts[0], ba = 0; arts.forEach(function (a) { var im = AL.img.get(a.img), ar = im ? im.w * im.h : 0; if (ar > ba) { ba = ar; best = a; } }); return best; }
  function roundSection(app, pb, n) {
    var ui = app.ui, bl = n.ball && n.ball.on !== false ? n.ball : null;
    pb.appendChild(ui.lab('Round shape', 'setup'));
    pb.appendChild(ui.toggle('It turns like a round thing', !!bl, function (v) { app.edit(function () { if (!v) { if (n.ball) n.ball.on = false; return; } if (n.ball) { n.ball.on = true; return; } var b = AL.boundsOf(n), arts = AL.findAll(n, function (q) { return q.type === 'art'; }); if (!b || !arts.length) return; n.ball = { cx: (b.x0 + b.x1) / 2, cy: (b.y0 + b.y1) / 2, rx: (b.x1 - b.x0) / 2, ry: (b.y1 - b.y0) / 2, yaw: 40, pitch: 20, kind: 'lathe', shell: biggest(arts).id }; }); }, 'A body, a belly, a barrel: the parts inside it can be painted on, and slide round it as the head turns'));
    if (!bl) return;
    pb.appendChild(ui.slider('Turns', { min: 0, max: 90, value: bl.yaw == null ? 50 : bl.yaw, set: function (v) { bl.yaw = v; }, fmt: function (v) { return v + '°'; }, title: 'How far round it goes when the head looks fully left or right. A body turns less than a head.' }));
    pb.appendChild(ui.slider('Nods', { min: 0, max: 60, value: bl.pitch == null ? 30 : bl.pitch, set: function (v) { bl.pitch = v; }, fmt: function (v) { return v + '°'; } }));
    roundExtra(app, pb, n, bl);
  }

  /* ── "follows": any part can ride any signal. This is how a frill lifts with the mouth or an ear twitches on a blink. ── */
  var SIGS = [['mouth', 'the mouth opening'], ['turnX', 'the head turning left and right'], ['turnY', 'the head turning up and down'], ['roll', 'the head tilting'], ['lookX', 'the eyes looking sideways'], ['lookY', 'the eyes looking up and down'], ['blinkL', 'a blink'], ['browL', 'the brows'], ['breath', 'breathing'], ['sway', 'the body leaning'], ['bounce', 'the body bouncing']];
  var PROPS = [['y', 'slides up and down'], ['x', 'slides sideways'], ['rot', 'turns'], ['s', 'grows and shrinks'], ['sy', 'stretches tall'], ['sx', 'stretches wide'], ['shake', 'shakes'], ['spin', 'spins']];
  function isScale(p) { return p === 's' || p === 'sx' || p === 'sy'; }
  function follows(app, pb, n) {
    var ui = app.ui, ch = app.ch; n.links = n.links || [];
    var SG = SIGS.concat([['always', 'all the time']], (ch.triggers || []).map(function (t) { return ['trig:' + t.id, 'the “' + t.name + '” button']; }), [['+new', 'a new button…']]);
    pb.appendChild(ui.lab('Follows', 'motion'));
    if (!n.links.length) pb.appendChild(ui.note('Make this part move with something else: lift with the mouth, shake with the voice, squash while a button is held, spin all the time.'));
    n.links.forEach(function (l, i) {
      var sName = (SG.filter(function (s) { return s[0] === l.sig; })[0] || [0, l.sig])[1], pName = (PROPS.filter(function (p) { return p[0] === l.prop; })[0] || [0, l.prop])[1], sc = isScale(l.prop);
      pb.appendChild(h('div', { class: 'rowf', style: 'margin-top:4px' }, [h('span', { class: 'note', style: 'margin:0;flex:1;min-width:0', html: 'It <b>' + pName + '</b> ' + (l.sig === 'always' ? '' : 'with ') + '<b>' + sName + '</b>' }), ui.chip('Bouncy', { on: !!l.bounce, title: 'It overshoots and settles, like something soft, instead of following exactly', fn: function () { app.edit(function () { l.bounce = !l.bounce; }, false, 'Bouncy'); } }), ui.chip('Back and forth', { on: !!l.osc, title: 'It rocks to and fro in time instead of holding still: a hover, a bob, a wag', fn: function () { app.edit(function () { if (l.osc) delete l.osc; else l.osc = 1; }, false, 'Back and forth'); } }), h('button', { class: 'ib', title: 'Remove this', onclick: function () { app.edit(function () { n.links.splice(i, 1); }, false, 'Remove a follow'); } }, AL.icon('x'))]));
      pb.appendChild(ui.slider('How much', { min: sc ? -1 : -40, max: sc ? 1 : 40, step: sc ? 0.02 : 0.5, value: sc ? clamp(l.amt, -1, 1) : l.amt, set: function (v) { l.amt = v; }, fmt: function (v) { return l.prop === 'spin' ? Math.round(v * 9) + '° a second' : round1(v) + (l.prop === 'rot' ? '°' : sc ? '×' : ' px'); } }));
      if (l.osc) pb.appendChild(ui.slider('To and fro', { min: 0.1, max: 6, step: 0.05, value: l.osc, set: function (v) { l.osc = v; }, fmt: function (v) { return round1(v) + ' a second'; } }));
    });
    var sigSel = h('select', { 'aria-label': 'Follows what' }, SG.map(function (s) { return h('option', { value: s[0], text: s[1] }); })), propSel = h('select', { 'aria-label': 'How it moves' }, PROPS.map(function (p) { return h('option', { value: p[0], text: p[1] }); }));
    function add(sig) { app.edit(function () { var pr = propSel.value; n.links.push({ sig: sig, prop: pr, amt: pr === 'rot' ? 8 : pr === 'spin' ? 10 : pr === 'shake' ? 2 : isScale(pr) ? 0.15 : 4 }); if (!n.pivot && n.type !== 'art') { var b = AL.boundsOf(n); if (b) n.pivot = { x: (b.x0 + b.x1) / 2, y: (b.y0 + b.y1) / 2 }; } }, false, 'Add a follow'); }
    pb.appendChild(h('div', { class: 'rowf', style: 'margin-top:6px' }, [propSel, h('span', { class: 'note', style: 'margin:0', text: 'with' }), sigSel, ui.chip('Add', { icon: 'plus', title: 'Add this link', fn: function () {
      if (sigSel.value !== '+new') return add(sigSel.value);
      app.promptText('Name the button (it shows in Perform)', 'Nervous', function (v) { var t = { id: AL.id('t'), name: v }; app.edit(function () { ch.triggers.push(t); }, true); add('trig:' + t.id); });
    } })]));
  }

  /* ── tools any part can have: carried by a limb, a loop, a stream of copies, a place on a round head ── */
  var COMPASS = ['→ right', '↘', '↓ down', '↙', '← left', '↖', '↑ up', '↗'];
  function compass(v) { return COMPASS[Math.round((((v % 360) + 360) % 360) / 45) % 8]; }
  function dirFromName(name, i, n) { var s = String(name).toLowerCase(), u = /\bup|top|high/.test(s), d = /down|low|bottom/.test(s), l = /left/.test(s), r = /right/.test(s); if (u || d || l || r) return Math.round(Math.atan2((d ? 1 : 0) - (u ? 1 : 0), (r ? 1 : 0) - (l ? 1 : 0)) * 180 / Math.PI); return Math.round(-180 + (i + 0.5) * 360 / n); }
  function hostName(q) { var r = AL.ROLES[q.role]; return q.type === 'set' ? (r ? r.label + (/^leg/.test(q.role) ? ': its foot' : ': its hand') : 'The end of “' + q.name + '”') : 'The tip of “' + q.name + '”'; }
  function tools(app, pb, n) {
    var ui = app.ui, ch = app.ch, e, p;
    var inLimb = (function () { var q = AL.parentOf(ch, n.id); return !!(n.limb || (q && q.pick && q.pick.by === 'reach')); })();
    if (!inLimb) {
      pb.appendChild(ui.lab('Size and turn', 'fit'));
      var needPivot = function () { if (!n.pivot && n.type !== 'art') { var b = AL.boundsOf(n); if (b) n.pivot = { x: (b.x0 + b.x1) / 2, y: (b.y0 + b.y1) / 2 }; } };
      pb.appendChild(ui.slider('Size', { min: 10, max: 300, step: 1, value: Math.round((n.sc == null ? 1 : n.sc) * 100), set: function (v) { needPivot(); n.sc = v / 100; }, fmt: function (v) { return v + '%'; }, title: 'Make this part bigger or smaller without redrawing it' }));
      pb.appendChild(ui.slider('Turn', { min: -180, max: 180, step: 1, value: n.rot || 0, set: function (v) { needPivot(); n.rot = v; }, fmt: function (v) { return v + '°'; } }));
      pb.appendChild(ui.row([ui.chip('Mirror', { icon: 'mirror', on: !!n.flip, title: 'Flip this part left to right (use one glove for both hands)', fn: function () { app.edit(function () { needPivot(); n.flip = !n.flip; }, false, 'Mirror'); } })]));
    }
    /* carried by the end of a limb or a swinging part */
    var hosts = AL.findAll(ch.root, function (q) { return q !== n && ((q.type === 'set' && q.pick && q.pick.by === 'reach') || (q.swing && q.swing.tip)) && !AL.find(n, function (d) { return d === q; }); });
    if (hosts.length || n.ride) {
      pb.appendChild(ui.lab('Carried by', 'setup'));
      var hostNow = n.ride ? AL.node(ch, n.ride.of) : null;
      pb.appendChild(ui.row([partChip(hostNow, hostNow ? hostName(hostNow) : 'Nothing: it stays where it is', 'Choose what carries this part: the hand of an arm, the foot of a leg, or the tip of something that swings', function () {
        pickPart(app, 'What carries “' + n.name + '”?', hosts, hostName, function (q) { app.edit(function () { if (!q) delete n.ride; else n.ride = { of: q.id, turn: n.ride && n.ride.turn != null ? n.ride.turn : 1 }; }, false, 'Carried by'); }, 'Nothing: it stays where it is');
      })]));
      if (n.ride) {
        pb.appendChild(ui.slider('Turns with it', { min: 0, max: 100, step: 5, value: Math.round((n.ride.turn == null ? 1 : n.ride.turn) * 100), set: function (v) { n.ride.turn = v / 100; }, fmt: function (v) { return v < 5 ? 'stays upright' : v > 95 ? 'fully' : v + '%'; }, title: 'A hand turns with its arm. A lantern on a tail stays upright.' }));
        pb.appendChild(ui.slider('Loose wrist', { min: 0, max: 100, step: 5, value: Math.round((n.ride.loose || 0) * 100), set: function (v) { n.ride.loose = v / 100; }, fmt: function (v) { return v < 5 ? 'stiff' : v > 70 ? 'floppy' : v + '%'; }, title: 'It lags behind the arm and overshoots when the arm stops, the way a real wrist does' }));
        pb.appendChild(ui.row([
          ui.chip(n.pivot ? 'Held where it was placed' : 'Set the grip point', { icon: 'pin', title: n.pivot ? 'Forget the grip pin: hold it exactly as it sits against the resting hand' : 'Add a pin on this drawing (its wrist, its handle). That point is put on the hand', fn: function () { app.edit(function () { if (n.pivot) delete n.pivot; else { var b = AL.boundsOf(n); n.pivot = b ? { x: (b.x0 + b.x1) / 2, y: (b.y0 + b.y1) / 2 } : { x: 0, y: 0 }; } }, false, 'Grip point'); } }),
          ui.chip('Always in front', { on: !!n.ride.front, title: 'Off: it goes behind the body when its arm does', fn: function () { app.edit(function () { n.ride.front = !n.ride.front; }, false, 'Always in front'); } })
        ]));
        {
          pb.appendChild(ui.toggle(n.type === 'set' ? 'All its drawings were drawn for one direction' : 'It was drawn for one direction', n.dir != null, function (v) { app.edit(function () { if (v) n.dir = -90; else delete n.dir; }); }, 'On: say which way the arm points in this drawing (a glove drawn fingers up was drawn for an arm pointing up), and it turns to match the arm. Off: it stays as you placed it against the resting hand'));
          if (n.dir != null) pb.appendChild(ui.slider('The arm points', { min: -180, max: 180, step: 5, value: n.dir, set: function (v) { n.dir = v; }, fmt: compass }));
        }
        if (n.type === 'set' && n.kids.length > 1) {
          var byDir = n.pick && n.pick.by === 'dir';
          pb.appendChild(ui.toggle('Swap drawings by the way it points', !!byDir, function (v) { app.edit(function () { n.pick = n.pick || {}; n.pick.by = v ? 'dir' : 'default'; if (v) n.kids.forEach(function (k, i) { if (k.dir == null) k.dir = dirFromName(k.name, i, n.kids.length); }); }); }, 'Each drawing is made for one direction of the arm. The nearest one shows, and turns the rest of the way.'));
          if (byDir) n.kids.forEach(function (k) { if (k.trig) return; var one = n.dir != null; pb.appendChild(h('div', { class: 'rowf', style: 'margin-top:8px' }, [cards.mini(k, 36), h('span', { class: 'note', style: 'margin:0;flex:1;min-width:0', text: k.name }), ui.chip('Mirror', { icon: 'mirror', on: !!k.flip, title: 'Flip this one drawing left to right (a thumb on the wrong side)', fn: function () { app.edit(function () { k.flip = !k.flip; }, false, 'Mirror ' + k.name); } })])); pb.appendChild(ui.slider(one ? 'Shows when' : 'Drawn for', { min: -180, max: 180, step: 5, value: (one ? k.when : k.dir) || 0, set: function (v) { if (one) k.when = v; else k.dir = v; }, fmt: compass, title: one ? 'Show this drawing when the arm points this way' : 'The way the arm points in this drawing' })); });
        }
      }
    }
    /* a loop */
    if (n.type === 'set' && n.kids && n.kids.length > 1) {
      var by = (n.pick && n.pick.by) || 'default';
      if (by === 'default') {
        pb.appendChild(ui.lab('Its looks', 'setup'));
        var restK = AL.node(ch, n.pick && n.pick.rest) || n.kids[0];
        pb.appendChild(h('div', { class: 'thumbs' }, n.kids.map(function (k, i) { return th(k, k.name, app.force[n.id] === k.id || (!app.force[n.id] && k === restK), k === restK, function () { app.force[n.id] = k.id; app.refreshPanel(true); }, k === restK ? 'Its usual look' : k.trig ? 'Shown by a button' : 'No button yet'); })));
        pb.appendChild(ui.row([
          ui.chip('A button for each look', { icon: 'bolt', title: 'Every drawing except the usual one gets a button in Perform. Drawings with the same name on another part share the button.', fn: function () { app.edit(function () { n.pick = n.pick || { by: 'default' }; if (!n.pick.rest) n.pick.rest = restK.id; n.kids.forEach(function (k) { if (k !== restK) AL.buttonFor(ch, k); }); }, false, 'Buttons for its looks'); app.toast('Buttons made. They are in Perform, and under Extras.'); } }),
          app.force[n.id] && app.force[n.id] !== restK.id ? ui.chip('Make this its usual look', { icon: 'star', fn: function () { var id = app.force[n.id]; app.edit(function () { n.pick = n.pick || { by: 'default' }; n.pick.rest = id; var k = AL.node(ch, id); if (k) delete k.trig; }, false, 'Usual look'); } }) : null
        ]));
        var selK = AL.node(ch, app.force[n.id]) || restK, selArt = selK.type === 'art' ? selK : AL.find(selK, function (q) { return q.type === 'art'; });
        pb.appendChild(ui.lab('The look “' + selK.name + '”', 'setup'));
        pb.appendChild(ui.row([
          ui.chip('Rename', { title: 'Change the name of this look, and of its button', fn: function () { app.promptText('Name this look', selK.name, function (v) { app.edit(function () { var t = selK.trig && (ch.triggers || []).filter(function (q) { return q.id === selK.trig; })[0]; if (t && t.name === selK.name) t.name = v; selK.name = v; }, false, 'Rename a look'); }); } }),
          selArt ? ui.chip('Change its picture', { icon: 'image', title: 'Put a different picture in this look. Its place, its button and its settings are kept.', fn: function () { AL.intake.swapPicture(app, selArt); } }) : null,
          ui.chip('Mirror', { icon: 'mirror', on: !!selK.flip, title: 'Flip this look left to right', fn: function () { app.edit(function () { selK.flip = !selK.flip; }, false, 'Mirror ' + selK.name); } }),
          n.kids.length > 1 ? ui.chip('Remove', { cls: 'quiet', icon: 'trash', title: 'Take this look out (Undo brings it back)', fn: function () { app.edit(function () { AL.remove(ch, selK.id); delete app.force[n.id]; if (n.pick && n.pick.rest === selK.id) delete n.pick.rest; }, false, 'Remove a look'); } }) : null
        ]));
        pb.appendChild(ui.row([
          ui.chip('Add looks', { icon: 'plus', title: 'Add more pictures as looks of this part', fn: function () { AL.intake.addLook(app, n); } }),
          ui.chip('Blend into another look…', { icon: 'wand', title: 'Mark the same spots on this look and another, and the change between them becomes one shape melting into the next instead of a swap', fn: function () { pickPart(app, 'Blend “' + selK.name + '” into which look?', n.kids.filter(function (q) { return q !== selK && q.type !== 'mirror'; }), null, function (q) { if (q) AL.bridge.open(app, n, selK, q); }); } })
        ]));
      }
      if (by === 'cycle' || by === 'default') {
        pb.appendChild(ui.lab('Loop', 'motion'));
        pb.appendChild(ui.toggle('Its drawings are frames of a loop', by === 'cycle', function (v) { app.edit(function () { n.pick = n.pick || {}; n.pick.by = v ? 'cycle' : 'default'; if (v && !n.pick.fps) n.pick.fps = 12; }); }, 'It plays through its drawings, top to bottom, over and over'));
        if (by === 'cycle') pb.appendChild(ui.slider('Loop speed', { min: 1, max: 30, value: n.pick.fps || 12, set: function (v) { n.pick.fps = v; }, fmt: function (v) { return v + ' a second'; } }));
      }
    }
    /* a stream of copies */
    e = n.emit && n.emit.on !== false ? n.emit : null;
    pb.appendChild(ui.lab('Stream', 'motion'));
    pb.appendChild(ui.toggle('Send it out as a stream of copies', !!e, function (v) { app.edit(function () { if (!v) { if (n.emit) n.emit.on = false; return; } if (n.emit) n.emit.on = true; else n.emit = { n: 4, life: 1.4, dx: 0, dy: -60, s0: 0.6, s1: 1.3, fade: 0.4 }; }); }, 'Hover rings, bubbles, sparks, steam, dust. Each copy drifts, grows and fades. It shows when you Try it.'));
    if (e) {
      pb.appendChild(ui.slider('How many', { min: 1, max: 16, value: e.n || 4, set: function (v) { e.n = v; } }));
      pb.appendChild(ui.slider('Each lasts', { min: 0.3, max: 6, step: 0.1, value: e.life || 1.4, set: function (v) { e.life = v; }, fmt: function (v) { return round1(v) + ' s'; } }));
      pb.appendChild(ui.slider('Drifts up / down', { min: -300, max: 300, step: 5, value: e.dy || 0, set: function (v) { e.dy = v; }, fmt: function (v) { return v < 0 ? 'up ' + (-v) : v > 0 ? 'down ' + v : 'stays'; } }));
      pb.appendChild(ui.slider('Drifts sideways', { min: -300, max: 300, step: 5, value: e.dx || 0, set: function (v) { e.dx = v; }, fmt: function (v) { return v < 0 ? 'left ' + (-v) : v > 0 ? 'right ' + v : 'stays'; } }));
      pb.appendChild(ui.slider('Starts at size', { min: 10, max: 300, step: 5, value: Math.round((e.s0 == null ? 1 : e.s0) * 100), set: function (v) { e.s0 = v / 100; }, fmt: function (v) { return v + '%'; } }));
      pb.appendChild(ui.slider('Ends at size', { min: 10, max: 300, step: 5, value: Math.round((e.s1 == null ? 1 : e.s1) * 100), set: function (v) { e.s1 = v / 100; }, fmt: function (v) { return v + '%'; } }));
      pb.appendChild(ui.slider('Fades out over', { min: 2, max: 100, value: Math.round((e.fade == null ? 0.35 : e.fade) * 100), set: function (v) { e.fade = v / 100; }, fmt: function (v) { return 'the last ' + v + '%'; } }));
      pb.appendChild(ui.slider('Turns as it goes', { min: -360, max: 360, step: 15, value: e.spin || 0, set: function (v) { e.spin = v; }, fmt: function (v) { return v + '°'; } }));
      pb.appendChild(ui.slider('Sways', { min: 0, max: 40, value: e.sway || 0, set: function (v) { e.sway = v; }, fmt: function (v) { return v ? v + ' px' : 'no'; } }));
      if (n.type === 'set' && n.pick && n.pick.by === 'cycle') pb.appendChild(ui.toggle('Each copy plays the loop once', !!n.pick.life, function (v) { app.edit(function () { n.pick.life = v; }); }, 'On: a copy starts on the first frame and ends on the last. Off: all copies keep time together'));
    }
    /* on a round head */
    p = AL.parentOf(ch, n.id); while (p && !(p.ball && p.ball.on !== false)) p = AL.parentOf(ch, p.id);
    if (p) {
      pb.appendChild(ui.lab('On the round shape', 'setup'));
      pb.appendChild(ui.row([ui.seg([['', 'Stays put', 'It does not move when the head turns'], ['on', 'Painted on', 'It slides over the head as the head turns, and squashes toward the edge'], ['out', 'Sticks out', 'It rides the head’s surface in one piece, and goes behind the head on the far side (ears, horns, hair)']], n.wrap || '', function (v) { app.edit(function () { if (v) n.wrap = v; else delete n.wrap; }); }, 'On the round head')]));
      if (n.wrap === 'out') pb.appendChild(ui.toggle('It lies flat against the head', !!n.flat, function (v) { app.edit(function () { n.flat = v; }); }, 'A disc or plate on the side of the head: thin from the front, round from the side'));
      if (n.wrap) pb.appendChild(ui.slider('Stands off the surface', { min: 0, max: 50, value: Math.round((n.lift || 0) * 100), set: function (v) { n.lift = v / 100; }, fmt: function (v) { return v ? v + '%' : 'flat on it'; }, title: 'A nose stands off the face, so it moves further and shows in profile' }));
      if (n.wrap === 'on' && n.type !== 'art') pb.appendChild(ui.slider('Keeps its shape', { min: 0, max: 100, step: 5, value: Math.round((n.keep == null ? 0.6 : n.keep) * 100), set: function (v) { n.keep = v / 100; }, fmt: function (v) { return v < 3 ? 'a sticker' : v > 97 ? 'never squashes' : v + '%'; }, title: 'A sticker squashes and leans exactly as the surface does. A cartoon eye stays upright and only narrows a little.' }));
      if (n.wrap) pb.appendChild(ui.toggle('It is on the back', !!n.back, function (v) { app.edit(function () { if (v) n.back = true; else delete n.back; }); }, 'A backpack, a tail, the back of the hair: it comes into view when the character is turned right round'));
    }
  }

  /** In a stance: the one drawing a hand uses while it is on the ground, shown and choosable, so there is nothing to guess. */
  function standsOn(app, pb, set) {
    if (!(set && set.type === 'set' && set.pick && set.pick.by === 'reach' && set.pick.hand && set.pick.hand.charAt(0) !== 'F') || !AL.walksOn(set)) return;
    var ui = app.ui, ch = app.ch, kids = (set.kids || []).filter(function (k) { return k.type === 'art' && k.limb; }); if (kids.length < 2) return;
    var cur = AL.node(ch, set.pick.walk && set.pick.walk.kid); if (!cur || kids.indexOf(cur) < 0) cur = AL.node(ch, set.pick.rest) || kids[0];
    pb.appendChild(ui.lab('The drawing it stands on', 'fit'));
    var one = !(set.pick.walk && set.pick.walk.any);
    pb.appendChild(ui.toggle('One drawing while it is on the ground', one, function (v) { app.edit(function () { var w = set.pick.walk = set.pick.walk || { lift: true }; if (v) delete w.any; else w.any = true; }, false, v ? 'One drawing on the ground' : 'Any drawing on the ground'); }, 'On: a hand on the ground uses the one drawing chosen below. Off: it swaps between its drawings as it reaches, as a free hand does.'));
    if (!one) return;
    pb.appendChild(h('div', { class: 'thumbs' }, kids.map(function (k, i) { return th(k, (i + 1) + '. ' + k.name, k === cur, set.pick.rest === k.id, function () { app.edit(function () { (set.pick.walk = set.pick.walk || { lift: true }).kid = k.id; }, false, 'Stands on drawing ' + (i + 1)); }, 'Use this drawing while the hand is on the ground'); })));
    pb.appendChild(ui.note('While this hand is on the ground it uses <b>one</b> drawing: the starred resting one unless you choose another here. Standing, reaching and waving keep all of their drawings.'));
  }
  /* ── one layer, whatever it is ── */
  function nodeCard(app, pb, n) {
    var ui = app.ui, ch = app.ch, role = n.role && AL.ROLES[n.role];
    var pz = AL.fit.pose(app), nm = cards.nameOf(app, n);
    head(app, nm, role ? role.label : (n.type === 'art' ? 'drawing' : n.type === 'set' ? 'one of' : 'folder'), pz ? 'Where it sits and what it is in front of, while the character is in the stance <b>' + pz.name + '</b>. Standing is not touched.' : 'Tell Avatar Studio what this is and it will rig it; or make it follow something.');
    app.coach(pz ? '<b>' + nm + ', in ' + pz.name + '.</b> Move it with the box, or change what it is in front of.' : '<b>' + nm + '.</b> One layer. Say what it is, make it swing, mask it, or make it follow something.');
    if (pz) { AL.fit.section(app, pb, n); standsOn(app, pb, n); pb.appendChild(ui.lab('The part itself', 'setup')); } else pb.appendChild(ui.lab('What it is', 'setup'));
    pb.appendChild(ui.row([
      ui.chip('What is this?', { icon: 'wand', cls: 'go', title: 'Say which part of the body this is', fn: function () { rolePicker(app, n); } }),
      ui.chip(n.hidden ? 'Hidden' : 'Shown', { on: !n.hidden, icon: n.hidden ? 'eyeoff' : 'eye', title: 'Show or hide this', fn: function () { app.edit(function () { if (n.hidden) delete n.hidden; else n.hidden = true; }); } }),
      ui.chip('Rename', { fn: function () { app.promptText('Name', n.name, function (v) { app.edit(function () { n.name = v; }); }); } }),
      ui.chip('Remove', { cls: 'quiet', icon: 'trash', title: 'Take this out of the character (Undo brings it back)', fn: function () { app.edit(function () { AL.remove(ch, n.id); app.sel.node = null; }); app.toast('Removed. Undo brings it back.'); } })
    ]));
    if (!pz) AL.fit.section(app, pb, n);
    pb.appendChild(ui.slider('Depth', { min: -1.5, max: 1.5, step: 0.1, value: n.depth || 0, set: function (v) { n.depth = v; }, fmt: function (v) { return v > 0.05 ? 'front ' + v : v < -0.05 ? 'behind ' + (-v) : 'flat'; } }));
    if (!AL.fit.pose(app) && !AL.stack.section(app, pb, n)) orderChips(app, pb, [n]);
    if (n.type === 'art') { pb.appendChild(ui.lab('Swinging', 'motion')); swingControls(app, pb, n); }
    var tset = AL.ancestorWith(ch, n.id, function (a) { return a.type === 'set' && a.pick && a.pick.by === 'turn'; });
    if (tset && tset !== n) pieceSection(app, pb, n, tset);
    follows(app, pb, n);
    shapesSection(app, pb, n);
    tools(app, pb, n);
    if (n.type !== 'art' && !(n.role === 'head' || n.role === 'face')) roundSection(app, pb, n);
    masksSection(app, pb, n);
    AL.tone.partToggle(app, pb, n);
    if (n.type === 'art' && n.mesh) meshPins(app, pb, n);
    if (n.swing) app.pinProvider = function () { return swingPins(n); };
    else if (n.pivot) app.pinProvider = function () { var p = [{ key: 'pv', label: 'Turns about', x: n.pivot.x, y: n.pivot.y, node: n.id, color: '#fbbf24', set: function (x, y) { n.pivot.x = x; n.pivot.y = y; } }]; if (n.mesh) n.mesh.pins.forEach(function (q) { p.push({ key: q.id, label: q.name, x: q.x, y: q.y, node: n.id, set: function (x, y) { q.x = x; q.y = y; } }); }); return p; };
    else if (n.mesh) app.pinProvider = function () { return n.mesh.pins.map(function (q) { return { key: q.id, label: q.name, x: q.x, y: q.y, node: n.id, set: function (x, y) { q.x = x; q.y = y; } }; }); };
    if (app.shapeEdit && app.shapeEdit.node === n.id) { var spp = shapePins(app, n); if (spp) app.pinProvider = spp; }
    if (app.fitOn && app.fitOn.box && app.fitOn.node === n.id) app.pinProvider = function () { return AL.fit.pins(app, n); };
  }
  function meshPins(app, pb, n) {
    pb.appendChild(app.ui.lab('Pins on this drawing', 'setup'));
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
      cards.mini(n, 22), h('span', { class: 'nm', text: n.name }), kind ? h('span', { class: 'k', text: kind }) : null,
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
