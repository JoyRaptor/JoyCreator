/* AvatarLab: bridges. Matching two drawings of the same thing so the change between them does not jump.
 *
 * On the Head card, small diamonds sit between the selected angle and each of its neighbours. Tapping one
 * opens this. ONE drawing is solid (full colour); the other is a tinted ghost over it. The colours never
 * swap: the first drawing is always CYAN (its dots, and its ghost), the second always MAGENTA.
 *
 * Marking a landmark is two taps and nothing moves under the finger:
 *   1. tap the landmark on the solid drawing            (a dot in the solid drawing's colour)
 *   2. tap the same landmark on the ghost               (a dot in the ghost's colour, joined to the first)
 * Every dot of BOTH drawings can be dragged at any time. Where two dots sit on top of each other the solid
 * drawing's dot is the one that is picked up; "Solid" chooses which drawing that is.
 *
 * A PIECE of the head (a frill, a horn, a snout) can have landmarks of its own, so it warps on its own
 * layer and slides over the rest; or it can warp with another piece; or with the whole head (the default).
 *   set.bridges  = [{ a, b, part?, pts:[{a:[x,y], b:[x,y]}], meet }]      part = the piece's name, lower case
 *   set.warpWith = { piece: 'own' | another piece's name }                absent = with the whole head
 *
 * The solver warps each drawing toward the other as the head turns between them (AL.bridgeFor, al-solve.js).
 * Phone: the pairs are plain data on the set; the warp is the puppet mesh engine's MLS, already built.
 */
(function (root) {
  'use strict';
  var AL = root.AL, h = AL.h, M = AL.M;
  function $(id) { return document.getElementById(id); }
  var bridge = AL.bridge = {};
  var COL = { a: '#22d3ee', b: '#f43f8e' }, WORD = { a: 'blue', b: 'pink' };
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
  function record(set, a, b, create, part) {
    var bs = set.bridges || (create ? (set.bridges = []) : []), pk = part || '';
    for (var i = 0; i < bs.length; i++) { if ((bs[i].part || '') !== pk) continue; if (bs[i].a === a && bs[i].b === b) return { br: bs[i], rev: false }; if (bs[i].a === b && bs[i].b === a) return { br: bs[i], rev: true }; }
    if (!create) return null;
    var br = { a: a, b: b, pts: [], meet: 0.5 }; if (pk) br.part = pk; bs.push(br); return { br: br, rev: false };
  }
  /** Landmarks between two drawings, counting every piece's own. */
  bridge.count = function (ch, set, A, B) { var n = 0; (set.bridges || []).forEach(function (q) { if ((q.a === A.id && q.b === B.id) || (q.a === B.id && q.b === A.id)) n += q.pts.length; }); if (!n) { var q2 = AL.bridgeFor(ch, set, A, B); n = q2 ? q2.length : 0; } return n; };
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
    /* a mirrored angle has no points of its own: match the pair it mirrors, and both sides are done */
    if (A.type === 'mirror' || B.type === 'mirror') {
      var a2 = A.type === 'mirror' ? AL.node(ch, A.of) : (A.at && Math.abs(A.at[0]) < 0.05 ? A : null), b2 = B.type === 'mirror' ? AL.node(ch, B.of) : (B.at && Math.abs(B.at[0]) < 0.05 ? B : null);
      if (a2 && b2 && a2 !== b2) { note = 'These are mirrored. Matching “' + a2.name + '” to “' + b2.name + '” does this side too.'; A = a2; B = b2; }
    }
    var r = record(set, A.id, B.id, false), a = r && r.rev ? B : A, b = r && r.rev ? A : B;
    app.bridge = { set: set.id, a: a.id, b: b.id, edit: a === A ? 'a' : 'b', sel: -1, t: null, pending: null, part: part || null, ids: null };
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
   *  Second tap: the same landmark on the ghost; only now is the pair saved, as ONE undo step. Nothing swaps. */
  bridge.tap = function (app, c, pin) {
    var b = app.bridge; if (!b || b.t != null || !app.out) return;
    var ch = app.ch, set = AL.node(ch, b.set), m = app.out.M[set.id] || app.out.rootM, l = M.ap(M.inv(m), c[0], c[1]);
    if (!b.pending) {
      if (pin) { if (pin.pick) pin.pick(); app.refreshPanel(true); return; }           // a tap on a dot picks it (to remove it, or just to see which it is)
      b.pending = { home: b.edit, p: [l[0], l[1]] }; b.sel = -1; app.refreshPanel(true);
    } else bridge.commit(app, [l[0], l[1]]);
  };
  bridge.commit = function (app, twin) {
    var b = app.bridge, set = AL.node(app.ch, b.set), pd = b.pending; if (!pd) return;
    var other = pd.home === 'a' ? 'b' : 'a';
    app.edit(function () {
      var r = record(set, b.a, b.b, true, b.part), q = {}; q[r.rev ? other : pd.home] = [pd.p[0], pd.p[1]]; q[r.rev ? pd.home : other] = twin || [pd.p[0], pd.p[1]]; r.br.pts.push(q); b.sel = r.br.pts.length - 1; b.pending = null;
      if (b.part && !bridge.warpsWith(set, b.part)) bridge.setWarpsWith(set, b.part, 'own');          // a piece with landmarks of its own warps on its own
    }, false, 'Match a landmark');
  };
  bridge.cancel = function (app) { var b = app.bridge; if (!b || !b.pending) return; b.pending = null; app.refreshPanel(true); };

  bridge.render = function (app, pb) {
    var ui = app.ui, ch = app.ch, b = app.bridge, set = AL.node(ch, b.set), A = AL.node(ch, b.a), B = AL.node(ch, b.b);
    if (!set || !A || !B) { app.bridge = null; return AL.cards.render(app); }
    var r = record(set, b.a, b.b, false, b.part), pts = r ? r.br.pts : [], ph = $('panelhead'), rev = !!(r && r.rev);
    function side(q, s) { return q[rev ? (s === 'a' ? 'b' : 'a') : s]; }                       // a record made the other way round keeps its points the other way round
    var turning = !!(A.at && B.at);
    var solid = b.edit === 'a' ? A : B, ghost = b.edit === 'a' ? B : A, gk = b.edit === 'a' ? 'b' : 'a', done = pts.length, pieces = bridge.pieces(ch, A, B);
    var what = b.part ? '“' + ((pieces.filter(function (p) { return p.key === b.part; })[0] || {}).name || b.part) + '”' : null;
    $('stagebar').innerHTML = ''; ph.innerHTML = '';
    ph.appendChild(h('div', { class: 'ptitle' }, [h('h2', null, [turning ? 'Match two angles' : 'Blend two looks', h('span', { class: 'badge' + (done ? ' ok' : ''), text: done + (done === 1 ? ' landmark' : ' landmarks') })]),
      h('p', { html: '<b style="color:' + COL.a + '">' + A.name + '</b> is always blue, <b style="color:' + COL.b + '">' + B.name + '</b> always pink. Mark the same spots on both' + (what ? ', on ' + what + ' only' : '') + ', and ' + (turning ? 'each drawing bends toward the other as the head turns.' : 'one melts into the other when the look changes.') })]));
    if (b.t != null) app.coach('<b>Previewing the turn.</b> Slide to watch one drawing become the other.');
    else if (b.pending) app.coach('<b>2 of 2.</b> Now tap the <b>same spot</b> on <b style="color:' + COL[gk] + '">' + ghost.name + '</b>, the ' + WORD[gk] + ' ghost.');
    else app.coach('<b>1 of 2.</b> Tap a landmark on <b style="color:' + COL[b.edit] + '">' + solid.name + '</b>, the full-colour drawing: a corner, a tip, an edge you can find again on the other one.');

    /* which piece is being matched */
    if (pieces.length > 1 && !b.pending) {
      pb.appendChild(ui.lab('What are you matching?'));
      var row = [h('button', { class: 'chip' + (!b.part ? ' on' : ''), title: 'Landmarks that bend every piece that has none of its own', 'aria-pressed': String(!b.part), onclick: function () { b.part = null; b.sel = -1; focusPart(app); app.refreshPanel(true); } }, [AL.cards.mini(A, 22), 'Whole head'])];
      pieces.forEach(function (p) {
        var w = bridge.warpsWith(set, p.key), rr = record(set, b.a, b.b, false, p.key), n = rr ? rr.br.pts.length : 0;
        row.push(h('button', { class: 'chip' + (b.part === p.key ? ' on' : ''), 'aria-pressed': String(b.part === p.key), title: '“' + p.name + '” ' + (w === 'own' ? 'warps on its own: ' + n + (n === 1 ? ' landmark' : ' landmarks') : w ? 'warps with “' + w + '”' : 'warps with the whole head') + '. Tap to give it landmarks of its own.', onclick: function () { b.part = p.key; b.sel = -1; focusPart(app); app.refreshPanel(true); } }, [AL.cards.mini(p.a, 22), p.name, w === 'own' ? h('small', { text: ' ' + n }) : null]));
      });
      pb.appendChild(h('div', { class: 'rowf wrapf' }, row));
      if (b.part) {
        var cur = bridge.warpsWith(set, b.part), opts = [['', 'the whole head'], ['own', 'its own landmarks']].concat(pieces.filter(function (p) { return p.key !== b.part && bridge.warpsWith(set, p.key) === 'own'; }).map(function (p) { return [p.key, '“' + p.name + '”']; }));
        pb.appendChild(h('div', { class: 'sl' }, [h('label', { text: 'It warps with' }), h('select', { 'aria-label': 'This piece warps with', title: 'A piece can bend with the whole head, with another piece, or by landmarks of its own', onchange: function (ev) { var v = ev.target.value; app.edit(function () { bridge.setWarpsWith(set, b.part, v); }, false, 'Warps with'); } }, opts.map(function (o) { return h('option', { value: o[0], text: o[1], selected: o[0] === cur }); })), h('span')]));
        if (cur && cur !== 'own') pb.appendChild(ui.note('It follows that piece’s landmarks. Choose <b>its own landmarks</b> to mark some here.'));
      }
    }
    if (b.pending) {
      pb.appendChild(ui.note('Landmark <b>' + (pts.length + 1) + '</b> is marked on ' + solid.name + '. Tap the same spot on the <b style="color:' + COL[gk] + '">' + WORD[gk] + ' ghost</b>. Nothing is saved until you do.'));
      pb.appendChild(ui.row([
        ui.chip('It is in the same place', { title: 'This landmark sits at the same spot on both drawings', fn: function () { bridge.commit(app, null); } }),
        ui.chip('Cancel this landmark', { cls: 'quiet', icon: 'trash', fn: function () { bridge.cancel(app); } })
      ]));
    } else {
      pb.appendChild(h('div', { class: 'sl' }, [h('label', { text: 'Solid' }), ui.seg([['a', A.name, 'Show “' + A.name + '” in full colour, with “' + B.name + '” as a pink ghost over it'], ['b', B.name, 'Show “' + B.name + '” in full colour, with “' + A.name + '” as a blue ghost over it']], b.edit, function (v) { b.edit = v; b.t = null; app.morph = null; show(app, v === 'a' ? A : B); app.refreshPanel(true); }, 'Which drawing is in full colour'), h('span')]));
      pb.appendChild(ui.slider(turning ? 'Preview the turn' : 'Preview the change', { min: 0, max: 100, value: b.t == null ? 0 : Math.round(b.t * 100), undo: false, set: function (v) { var first = b.t == null; b.t = v / 100; if (turning) { app.bt.x = A.at[0] + (B.at[0] - A.at[0]) * b.t; app.bt.y = A.at[1] + (B.at[1] - A.at[1]) * b.t; } else app.morph = { set: set.id, a: A.id, b: B.id, t: b.t }; if (first) app.refreshPanel(true); },
        fmt: function (v) { return v < 3 ? A.name.slice(0, 6) : v > 97 ? B.name.slice(0, 6) : v + '%'; }, title: 'Scrub from one drawing to the other to see the tween' }));
      if (r) pb.appendChild(ui.slider('They meet', { min: 0, max: 100, value: Math.round((r.br.meet == null ? 0.5 : r.br.meet) * 100), set: function (v) { r.br.meet = v / 100; }, fmt: function (v) { return v < 40 ? 'nearer ' + side({ a: A, b: B }, 'a').name.slice(0, 5) : v > 60 ? 'nearer ' + side({ a: A, b: B }, 'b').name.slice(0, 5) : 'half way'; }, title: 'Where the two drawings meet at the moment of the swap. Slide toward the one that should bend less.' }));
      var row2 = [];
      if (b.t != null) row2.push(ui.chip('Back to marking', { icon: 'back', fn: function () { b.t = null; app.morph = null; show(app, b.edit === 'a' ? A : B); app.refreshPanel(true); } }));
      if (b.sel >= 0 && pts[b.sel]) row2.push(ui.chip('Remove landmark ' + (b.sel + 1), { icon: 'trash', fn: function () { app.edit(function () { pts.splice(b.sel, 1); b.sel = -1; }, false, 'Remove a landmark'); } }));
      if (pts.length) row2.push(ui.chip('Clear all', { cls: 'quiet', fn: function () { app.edit(function () { pts.length = 0; b.sel = -1; }, false, 'Clear the landmarks'); } }));
      row2.push(ui.chip('Done', { cls: 'go', fn: function () { bridge.close(app); } }));
      pb.appendChild(ui.row(row2));
      pb.appendChild(ui.note(pts.length ? 'Drag <b>any</b> dot to move it: blue dots belong to ' + A.name + ', pink dots to ' + B.name + '. Where two dots overlap, the full-colour drawing’s dot is the one you pick up. Three to eight landmarks is usually enough.' : 'Each landmark is two taps: first on the full-colour drawing, then the same spot on the ghost. Nothing swaps under your finger.'));
    }
    app.pinProvider = function () {
      if (b.t != null) return [];
      var out = [];
      pts.forEach(function (q, i) {
        ['a', 'b'].forEach(function (s) {
          var top = s === b.edit, p = side(q, s);
          out.push({ key: s + i, label: top || b.sel === i ? String(i + 1) : null, x: p[0], y: p[1], node: set.id, to: s === 'a' ? 'b' + i : null, color: COL[s], small: !top, prio: top ? 7 : 0, hot: b.sel === i, fixed: !!b.pending, set: function (x, y) { p[0] = x; p[1] = y; }, pick: function () { b.sel = i; } });
        });
      });
      /* while the second tap is owed, only the waiting dot can be dragged, and only from right on it: a tap anywhere else places its twin */
      if (b.pending) out.push({ key: 'pending', x: b.pending.p[0], y: b.pending.p[1], node: set.id, color: COL[b.pending.home], label: String(pts.length + 1), pulse: true, rad: 13, set: function (x, y) { b.pending.p[0] = x; b.pending.p[1] = y; } });
      return out;
    };
  };
})(typeof self !== 'undefined' ? self : this);
