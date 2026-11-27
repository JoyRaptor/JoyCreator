/* AvatarLab: LANDMARKS. Matching the drawings of one thing so the change between them does not jump.
 *
 * The owner's model (11-23, spec tasks/avatar/AVA_PHONE_SPEC.md A9, format AVA_FORMAT.md B2.1): each piece of a set (the whole head,
 * or a piece such as the front frill that warps on its own) has ONE numbered set of landmarks, and every drawing places the SAME
 * points. Point 3 is point 3 in every angle. Each point, in each drawing, is
 *   here       seen in this drawing
 *   behind     folded round the back (the crest's far tips in profile): a ghost that still has a place and still steers the bend
 *   not here   takes no part in this drawing (a grey dot with a line through it; it can sit anywhere)
 * Kept as set.marks (al-format.js). The pilot's older pairs (set.bridges) are converted when a file is opened.
 *
 * On the Head card, small diamonds sit between the selected angle and each of its neighbours. Tapping one opens this. ONE drawing is
 * solid (full colour); the other is a tinted ghost over it (or, onion skin off, in its own colours with a See-through slider). The
 * colours never swap: the first drawing is always CYAN (its dots, and its ghost), the second always MAGENTA.
 *
 * A new landmark is two taps and nothing moves under the finger:
 *   1. tap the spot on the solid drawing            (a dot in the solid drawing's colour)
 *   2. tap the same spot on the ghost               (a dot in the ghost's colour, joined to the first)
 * It is then "not here" in every other drawing, parked where it was tapped, ready to be dragged into place there. Every dot of both
 * drawings can be dragged at any time; a "not here" dot that is dragged becomes "here"; a dot on the solid drawing dropped off the
 * drawing's pixels is guessed "behind" (one tap on Here puts it back). Choose a point, then Here, Behind or Not here.
 *
 *   set.warpWith = { piece: 'own' | another piece's name }                absent = with the whole head
 * The solver warps each drawing toward the other as the head turns between them (AL.bridgeFor, al-solve.js).
 * Phone: R/solve/Marks and R/ui/LandmarksPanel (AVA_PORT_MAP.md); the warp is the puppet mesh engine's MLS, already built.
 */
(function (root) {
  'use strict';
  var AL = root.AL, h = AL.h, M = AL.M, MK = AL.marks;
  function $(id) { return document.getElementById(id); }
  var bridge = AL.bridge = {};
  var COL = { a: '#22d3ee', b: '#f43f8e' }, WORD = { a: 'blue', b: 'pink' }, GREY = '#71717a';
  bridge.TINT = { a: [0.13, 0.83, 0.93, 0.6], b: [0.96, 0.25, 0.56, 0.6] };
  function key(n) { return String(n.name || '').toLowerCase().trim(); }
  bridge.key = key;

  /** Angles whose territory touches this one's: the midpoint between the two is nearer to them than to any other. */
  bridge.neighbours = function (set, kid) {
    var out = []; if (!kid || !kid.at) return out;
    set.kids.forEach(function (o) {
      if (o === kid || !o.at) return;
      var mx = (kid.at[0] + o.at[0]) / 2, my = (kid.at[1] + o.at[1]) / 2, d = Math.hypot(kid.at[0] - mx, kid.at[1] - my), ok = true;
      set.kids.forEach(function (k) { if (k !== kid && k !== o && k.at && Math.hypot(k.at[0] - mx, k.at[1] - my) < d - 0.02) ok = false; });
      if (ok) out.push(o);
    });
    return out;
  };
  /** Landmarks two drawings share (here or behind in both), counting every piece's own. */
  bridge.count = function (ch, set, A, B) { var n = 0; Object.keys(set.marks || {}).forEach(function (pk) { var q = AL.bridgeFor(ch, set, A, B, pk); n += q ? q.length : 0; }); return n; };
  function show(app, kid) { if (kid && kid.at) { app.bt.x = kid.at[0]; app.bt.y = kid.at[1]; } }

  /** The pieces two drawings both have (a layer or folder of the same name in each). */
  bridge.pieces = function (ch, A, B) {
    var a = A.type === 'mirror' ? AL.node(ch, A.of) : A, b = B.type === 'mirror' ? AL.node(ch, B.of) : B, out = [], seen = {};
    if (!a || !b || !a.kids || !b.kids) return out;
    a.kids.forEach(function (k) { var kk = key(k); if (!kk || seen[kk] || k.type === 'mirror') return; var tw = b.kids.filter(function (q) { return key(q) === kk; })[0]; if (tw) { seen[kk] = 1; out.push({ key: kk, name: k.name, a: k, b: tw }); } });
    return out;
  };
  /** How a piece of a turning head warps: '' with the whole head, 'own', or the name of the piece it warps with. */
  bridge.warpsWith = function (set, k) { return (set.warpWith && set.warpWith[k]) || ''; };
  bridge.setWarpsWith = function (set, k, v) { set.warpWith = set.warpWith || {}; if (v) set.warpWith[k] = v; else delete set.warpWith[k]; if (!Object.keys(set.warpWith).length) delete set.warpWith; };

  bridge.open = function (app, set, A, B, part) {
    var ch = app.ch, note = null;
    /* a mirrored angle has no points of its own: match the drawing it mirrors, and both sides are done */
    if (A.type === 'mirror' || B.type === 'mirror') {
      var a2 = A.type === 'mirror' ? AL.node(ch, A.of) : (A.at && Math.abs(A.at[0]) < 0.05 ? A : null), b2 = B.type === 'mirror' ? AL.node(ch, B.of) : (B.at && Math.abs(B.at[0]) < 0.05 ? B : null);
      if (a2 && b2 && a2 !== b2) { note = 'These are mirrored. Matching “' + a2.name + '” to “' + b2.name + '” does this side too.'; A = a2; B = b2; }
    }
    app.bridge = { set: set.id, a: A.id, b: B.id, edit: 'a', sel: -1, t: null, pending: null, part: part || null, ids: null };
    focusPart(app);
    show(app, A); app.tab = 'parts'; app.sel = { zone: AL.zoneOf(ch, set.id) || 'head', feature: null, node: null }; app.resolveSel(); app.userView = false;
    app.refreshPanel(); app.focusSel(); if (note) app.toast(note);
  };
  /* working on one piece: everything else in both drawings steps back */
  function focusPart(app) {
    var b = app.bridge, ch = app.ch; b.ids = null; if (!b.part) return;
    var ids = {}; [AL.node(ch, b.a), AL.node(ch, b.b)].forEach(function (g) { if (g) AL.walk(g, function (n) { if (n !== g && key(n) === b.part) { AL.walk(n, function (q) { ids[q.id] = 1; }); return false; } }); });
    b.ids = ids;
  }
  bridge.close = function (app) { var b = app.bridge; app.bridge = null; app.morph = null; if (b) show(app, AL.node(app.ch, b[b.edit])); app.resolveSel(); app.refreshPanel(); };

  /** A tap on the stage while matching. First tap: the landmark on the solid drawing (kept aside, not saved).
   *  Second tap: the same landmark on the ghost; only now is it saved, as ONE undo step. Nothing swaps. */
  bridge.tap = function (app, c, pin) {
    var b = app.bridge; if (!b || b.t != null || !app.out) return;
    var ch = app.ch, set = AL.node(ch, b.set), m = app.out.M[set.id] || app.out.rootM, l = M.ap(M.inv(m), c[0], c[1]);
    if (!b.pending) {
      if (pin) { if (pin.pick) pin.pick(); app.refreshPanel(true); return; }           // a tap on a dot picks it (to change it, remove it, or just to see which it is)
      b.pending = { home: b.edit, p: [l[0], l[1]] }; b.sel = -1; app.refreshPanel(true);
    } else bridge.commit(app, [l[0], l[1]]);
  };
  bridge.commit = function (app, twin) {
    var b = app.bridge, set = AL.node(app.ch, b.set), pd = b.pending; if (!pd) return;
    var other = pd.home === 'a' ? 'b' : 'a';
    app.edit(function () {
      var mk = MK.piece(set, b.part, true), pl = {};
      pl[b[pd.home]] = [pd.p[0], pd.p[1]]; pl[b[other]] = twin || [pd.p[0], pd.p[1]];
      b.sel = MK.add(mk, pl); b.pending = null;
      if (b.part && !bridge.warpsWith(set, b.part)) bridge.setWarpsWith(set, b.part, 'own');          // a piece with landmarks of its own warps on its own
    }, false, 'Add a landmark');
  };
  bridge.cancel = function (app) { var b = app.bridge; if (!b || !b.pending) return; b.pending = null; app.refreshPanel(true); };

  /* is a spot (in the set's space) on the pixels of this drawing? (the guess for "behind") */
  function onDrawing(app, set, kid, x, y) {
    if (!app.out) return true;
    var m = app.out.M[set.id] || app.out.rootM, c = M.ap(m, x, y), ids = {}; AL.walk(kid, function (q) { ids[q.id] = 1; });
    return !!AL.hit(app.out, c[0], c[1], null, ids);
  }

  var STATES = [['here', 'Here', 'Here: this spot is seen in this drawing'], ['behind', 'Behind', 'Behind: this spot is round the back here, but still steers the turn'], ['off', 'Not here', 'Not here: this spot does not exist in this drawing']];

  bridge.render = function (app, pb) {
    var ui = app.ui, ch = app.ch, b = app.bridge, set = AL.node(ch, b.set), A = AL.node(ch, b.a), B = AL.node(ch, b.b);
    if (!set || !A || !B) { app.bridge = null; return AL.cards.render(app); }
    var mk = MK.piece(set, b.part, false), pts = mk ? mk.pts : [], ph = $('panelhead');
    var turning = !!(A.at && B.at), ids = { a: A.id, b: B.id }, node = { a: A, b: B };
    var solid = b.edit === 'a' ? A : B, ghost = b.edit === 'a' ? B : A, gk = b.edit === 'a' ? 'b' : 'a', pieces = bridge.pieces(ch, A, B);
    var shared = (AL.bridgeFor(ch, set, A, B, b.part || '') || []).length;
    if (b.sel >= pts.length) b.sel = -1;
    var what = b.part ? '“' + ((pieces.filter(function (p) { return p.key === b.part; })[0] || {}).name || b.part) + '”' : null;
    $('stagebar').innerHTML = ''; ph.innerHTML = '';
    ph.appendChild(h('div', { class: 'ptitle' }, [h('h2', null, [turning ? 'Landmarks' : 'Blend two looks', h('span', { class: 'badge' + (shared ? ' ok' : ''), title: 'Landmarks both drawings use (here or behind in both)', text: shared + ' of ' + pts.length + (pts.length === 1 ? ' landmark' : ' landmarks') })]),
      h('p', { html: (what ? what + ' has' : 'Every ' + (turning ? 'angle' : 'look') + ' has') + ' the same numbered landmarks: put each where that spot is in each drawing. <b style="color:' + COL.a + '">' + A.name + '</b> is always blue, <b style="color:' + COL.b + '">' + B.name + '</b> always pink, ' + (turning ? 'and each drawing bends toward the other as the head turns.' : 'and one melts into the other when the look changes.') })]));
    if (b.t != null) app.coach('<b>Previewing the turn.</b> Slide to watch one drawing become the other.');
    else if (b.pending) app.coach('<b>2 of 2.</b> Now tap the <b>same spot</b> on <b style="color:' + COL[gk] + '">' + ghost.name + '</b>, the ' + WORD[gk] + ' ghost.');
    else if (b.sel >= 0) app.coach('<b>Landmark ' + (b.sel + 1) + '.</b> Drag it to the same spot on each drawing, or say whether it is here, behind or not here on <b style="color:' + COL[b.edit] + '">' + solid.name + '</b>.');
    else app.coach('<b>1 of 2.</b> Tap a landmark on <b style="color:' + COL[b.edit] + '">' + solid.name + '</b>, the full-colour drawing: a corner, a tip, an edge you can find again on the other one.');

    /* which piece is being matched */
    if (pieces.length > 1 && !b.pending) {
      pb.appendChild(ui.lab('What are you matching?'));
      var row = [h('button', { class: 'chip' + (!b.part ? ' on' : ''), title: 'Landmarks that bend every piece that has none of its own', 'aria-pressed': String(!b.part), onclick: function () { b.part = null; b.sel = -1; focusPart(app); app.refreshPanel(true); } }, [AL.cards.mini(A, 22), 'Whole head'])];
      pieces.forEach(function (p) {
        var w = bridge.warpsWith(set, p.key), pm = MK.piece(set, p.key, false), n = pm ? pm.pts.length : 0;
        row.push(h('button', { class: 'chip' + (b.part === p.key ? ' on' : ''), 'aria-pressed': String(b.part === p.key), title: '“' + p.name + '” ' + (w === 'own' ? 'warps on its own: ' + n + (n === 1 ? ' landmark' : ' landmarks') : w ? 'warps with “' + w + '”' : 'warps with the whole head') + '. Tap to give it landmarks of its own.', onclick: function () { b.part = p.key; b.sel = -1; focusPart(app); app.refreshPanel(true); } }, [AL.cards.mini(p.a, 22), p.name, w === 'own' ? h('small', { text: ' ' + n }) : null]));
      });
      pb.appendChild(h('div', { class: 'rowf wrapf' }, row));
      if (b.part) {
        var cur = bridge.warpsWith(set, b.part), opts = [['', 'the whole head'], ['own', 'its own landmarks']].concat(pieces.filter(function (p) { return p.key !== b.part && bridge.warpsWith(set, p.key) === 'own'; }).map(function (p) { return [p.key, '“' + p.name + '”']; }));
        pb.appendChild(h('div', { class: 'sl' }, [h('label', { text: 'It warps with' }), h('select', { 'aria-label': 'This piece warps with', title: 'A piece can bend with the whole head, with another piece, or by landmarks of its own', onchange: function (ev) { var v = ev.target.value; app.edit(function () { bridge.setWarpsWith(set, b.part, v); }, false, 'Warps with'); } }, opts.map(function (o) { return h('option', { value: o[0], text: o[1], selected: o[0] === cur }); })), h('span')]));
        if (cur && cur !== 'own') pb.appendChild(ui.note('It follows that piece’s landmarks. Choose <b>its own landmarks</b> to mark some here.'));
      }
    }
    /* onion skin off (the round button on the stage): the other drawing in its own colours over this one, as see-through as you like */
    if (b.t == null && b.tint === false) pb.appendChild(ui.slider('See-through', { min: 0, max: 100, step: 5, value: Math.round((b.see == null ? 0.5 : b.see) * 100), undo: false, set: function (v) { b.see = v / 100; }, fmt: function (v) { return v < 3 ? 'only ' + solid.name.slice(0, 6) : v > 97 ? 'only ' + ghost.name.slice(0, 6) : v + '%'; }, title: 'How much of ' + ghost.name + ' shows over ' + solid.name + ', in its own colours. Slide to find faint shading on both, or switch Solid to swap which is on top.' }));
    if (b.pending) {
      pb.appendChild(ui.note('Landmark <b>' + (pts.length + 1) + '</b> is marked on ' + solid.name + '. Tap the same spot on the <b style="color:' + COL[gk] + '">' + WORD[gk] + ' ghost</b>. Nothing is saved until you do. In every other drawing it starts as “not here”, ready to be dragged into place.'));
      pb.appendChild(ui.row([
        ui.chip('It is in the same place', { title: 'This landmark sits at the same spot on both drawings', fn: function () { bridge.commit(app, null); } }),
        ui.chip('Cancel this landmark', { cls: 'quiet', icon: 'trash', title: 'Forget this tap: nothing was saved', fn: function () { bridge.cancel(app); } })
      ]));
    } else {
      pb.appendChild(h('div', { class: 'sl' }, [h('label', { text: 'Solid' }), ui.seg([['a', A.name, 'Show “' + A.name + '” in full colour, with “' + B.name + '” as a pink ghost over it'], ['b', B.name, 'Show “' + B.name + '” in full colour, with “' + A.name + '” as a blue ghost over it']], b.edit, function (v) { b.edit = v; b.t = null; app.morph = null; show(app, v === 'a' ? A : B); app.refreshPanel(true); }, 'Which drawing is in full colour'), h('span')]));
      /* the chosen landmark, in the solid drawing: here, behind or not here */
      if (b.sel >= 0 && pts[b.sel] && b.t == null) {
        var rs = mk.at[ids[b.edit]], st = MK.stateOf(rs ? rs[b.sel] : null);
        pb.appendChild(ui.lab('Landmark ' + (b.sel + 1) + ' in ' + solid.name));
        pb.appendChild(h('div', { class: 'sl' }, [h('label', { text: 'It is' }), ui.seg(STATES, st, function (v) {
          if (v === st) return;
          app.edit(function () { var m2 = MK.piece(set, b.part, true); MK.row(m2, ids[b.edit], ids[gk]); MK.setState(m2, ids[b.edit], b.sel, v); }, false, 'Landmark ' + (b.sel + 1) + ': ' + (v === 'off' ? 'not here' : v));
        }, 'Is this landmark seen in ' + solid.name + '?'), h('span')]));
      }
      pb.appendChild(ui.slider(turning ? 'Preview the turn' : 'Preview the change', { min: 0, max: 100, value: b.t == null ? 0 : Math.round(b.t * 100), undo: false, set: function (v) { var first = b.t == null; b.t = v / 100; if (turning) { app.bt.x = A.at[0] + (B.at[0] - A.at[0]) * b.t; app.bt.y = A.at[1] + (B.at[1] - A.at[1]) * b.t; } else app.morph = { set: set.id, a: A.id, b: B.id, t: b.t }; if (first) app.refreshPanel(true); },
        fmt: function (v) { return v < 3 ? A.name.slice(0, 6) : v > 97 ? B.name.slice(0, 6) : v + '%'; }, title: 'Scrub from one drawing to the other to see the tween' }));
      if (mk && mk.at[A.id] && mk.at[B.id]) pb.appendChild(ui.slider('They meet', { min: 0, max: 100, value: Math.round(MK.meet(mk, A.id, B.id) * 100), set: function (v) { MK.setMeet(mk, A.id, B.id, v / 100); }, fmt: function (v) { return v < 40 ? 'nearer ' + A.name.slice(0, 5) : v > 60 ? 'nearer ' + B.name.slice(0, 5) : 'half way'; }, title: 'Where the two drawings meet on the way between them. Slide toward the one that should bend less.' }));
      var row2 = [];
      if (b.t != null) row2.push(ui.chip('Back to marking', { icon: 'back', title: 'Stop previewing and mark landmarks again', fn: function () { b.t = null; app.morph = null; show(app, b.edit === 'a' ? A : B); app.refreshPanel(true); } }));
      if (b.sel >= 0 && pts[b.sel]) row2.push(ui.chip('Remove landmark ' + (b.sel + 1), { icon: 'trash', title: 'Take landmark ' + (b.sel + 1) + ' out of every ' + (turning ? 'angle' : 'look') + '. The others are numbered again.', fn: function () { var i = b.sel; app.edit(function () { MK.remove(set, b.part, i); b.sel = -1; }, false, 'Remove landmark ' + (i + 1)); } }));
      if (pts.length) row2.push(ui.chip('Clear all', { cls: 'quiet', title: 'Take every landmark of ' + (what || 'the whole head') + ' out of every drawing. Undo brings them back.', fn: function () { app.edit(function () { MK.clear(set, b.part); b.sel = -1; }, false, 'Clear the landmarks'); } }));
      row2.push(ui.chip('Done', { cls: 'go', title: 'Done matching', fn: function () { bridge.close(app); } }));
      pb.appendChild(ui.row(row2));
      pb.appendChild(ui.note(pts.length ? 'Drag <b>any</b> dot to move it in that drawing: blue dots belong to ' + A.name + ', pink dots to ' + B.name + '. A <b>grey dot with a line</b> is not here in that drawing: drag it to where the spot is and it counts. A <b>ring</b> is behind: round the back, still steering. Three to eight landmarks is usually enough.' : 'Each landmark is two taps: first on the full-colour drawing, then the same spot on the ghost. Nothing swaps under your finger.'));
    }
    app.pinProvider = function () {
      if (b.t != null) return [];
      var out = [], m3 = MK.piece(set, b.part, false); if (!m3) m3 = { pts: [], at: {} };
      m3.pts.forEach(function (p, i) {
        /* a point "not here" in BOTH drawings on show belongs to other angles: it is left out, unless it is the one chosen */
        var ra = m3.at[ids.a], rb = m3.at[ids.b];
        if (b.sel !== i && MK.off(ra && ra[i]) && MK.off(rb && rb[i])) return;
        ['a', 'b'].forEach(function (s) {
          var id = ids[s], o = s === 'a' ? 'b' : 'a', row = m3.at[id], q = row ? row[i] : null, top = s === b.edit;
          if (!q) { var r2 = m3.at[ids[o]]; if (!r2 || !r2[i]) return; q = [r2[i][0], r2[i][1], 'off']; }      // (no row yet in this drawing: shown "not here", where the other has it)
          var st = MK.stateOf(q), at = [q[0], q[1]];
          out.push({ key: s + i, label: top || b.sel === i ? String(i + 1) : null, x: at[0], y: at[1], node: set.id, to: s === 'a' ? 'b' + i : null, color: st === 'off' ? GREY : COL[s], state: st, small: !top, prio: top ? 7 : 0, hot: b.sel === i, fixed: !!b.pending,
            undo: 'Move landmark ' + (i + 1),
            set: function (x, y) { var m4 = MK.piece(set, b.part, true); MK.row(m4, id, ids[o]); var was = MK.stateOf(m4.at[id][i]); MK.move(m4, id, i, x, y); if (was === 'off') MK.setState(m4, id, i, 'here'); at[0] = x; at[1] = y; },
            /* the guess: a dot on the solid drawing let go off that drawing's pixels is behind it (Here puts it back) */
            drop: function () { if (!top) return; var m5 = MK.piece(set, b.part, false), r5 = m5 && m5.at[id]; if (!r5 || !r5[i] || MK.stateOf(r5[i]) !== 'here') return; if (!onDrawing(app, set, node[s], r5[i][0], r5[i][1])) { MK.setState(m5, id, i, 'behind'); app.toast('Landmark ' + (i + 1) + ' is off the drawing, so it is behind there. Tap Here if it is not.'); } },
            pick: function () { b.sel = i; } });
        });
      });
      /* while the second tap is owed, only the waiting dot can be dragged, and only from right on it: a tap anywhere else places its twin */
      if (b.pending) out.push({ key: 'pending', x: b.pending.p[0], y: b.pending.p[1], node: set.id, color: COL[b.pending.home], label: String(m3.pts.length + 1), pulse: true, rad: 13, set: function (x, y) { b.pending.p[0] = x; b.pending.p[1] = y; } });
      return out;
    };
  };
})(typeof self !== 'undefined' ? self : this);
