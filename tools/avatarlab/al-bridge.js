/* AvatarLab: bridges. Matching two neighbouring head angles so the swap between them does not jump.
 *
 * On the Head card, small diamonds sit between the selected angle and each of its neighbours. Tapping one
 * opens this. Marking a landmark is TWO TAPS, one drawing at a time:
 *   1. tap the landmark (a crest spike, an eye corner) on the solid drawing;
 *   2. the other drawing becomes the solid one: tap the same landmark there. Back to the first drawing.
 * Only the solid drawing's points can be touched, so nothing is ever grabbed from underneath. The ghost's
 * points show as small dots joined to their twins. "On top" swaps which drawing is solid, to adjust its points.
 *
 * The solver warps each drawing toward the other as the head turns between them (AL.bridgeFor, al-solve.js).
 * Phone: the pairs are plain data on the turn set; the warp is the puppet mesh engine's MLS, already built.
 */
(function (root) {
  'use strict';
  var AL = root.AL, h = AL.h, M = AL.M;
  function $(id) { return document.getElementById(id); }
  var bridge = AL.bridge = {};
  var COL = { a: '#22d3ee', b: '#f43f8e' };

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
  function record(set, a, b, create) {
    var bs = set.bridges || (create ? (set.bridges = []) : []);
    for (var i = 0; i < bs.length; i++) { if (bs[i].a === a && bs[i].b === b) return { br: bs[i], rev: false }; if (bs[i].a === b && bs[i].b === a) return { br: bs[i], rev: true }; }
    if (!create) return null;
    var br = { a: a, b: b, pts: [], meet: 0.5 }; bs.push(br); return { br: br, rev: false };
  }
  bridge.count = function (ch, set, A, B) { var q = AL.bridgeFor(ch, set, A, B); return q ? q.length : 0; };
  function show(app, kid) { if (kid && kid.at) { app.bt.x = kid.at[0]; app.bt.y = kid.at[1]; } }

  bridge.open = function (app, set, A, B) {
    var ch = app.ch, note = null;
    /* a mirrored angle has no points of its own: match the pair it mirrors, and both sides are done */
    if (A.type === 'mirror' || B.type === 'mirror') {
      var a2 = A.type === 'mirror' ? AL.node(ch, A.of) : (A.at && Math.abs(A.at[0]) < 0.05 ? A : null), b2 = B.type === 'mirror' ? AL.node(ch, B.of) : (B.at && Math.abs(B.at[0]) < 0.05 ? B : null);
      if (a2 && b2 && a2 !== b2) { note = 'These are mirrored. Matching “' + a2.name + '” to “' + b2.name + '” does this side too.'; A = a2; B = b2; }
    }
    var r = record(set, A.id, B.id, false), a = r && r.rev ? B : A, b = r && r.rev ? A : B;
    app.bridge = { set: set.id, a: a.id, b: b.id, edit: a === A ? 'a' : 'b', sel: -1, t: null, pending: null };
    show(app, A); app.tab = 'parts'; app.sel = { zone: 'head', feature: null, node: null }; app.resolveSel(); app.userView = false;
    app.refreshPanel(); app.focusSel(); if (note) app.toast(note);
  };
  bridge.close = function (app) { var b = app.bridge; app.bridge = null; if (b) show(app, AL.node(app.ch, b.a)); app.resolveSel(); app.refreshPanel(); };

  /** A tap on the stage while matching. First tap: the landmark on the solid drawing (kept aside, not saved).
   *  Second tap: the same landmark on the other drawing; only now is the pair saved, as ONE undo step. */
  bridge.tap = function (app, c, pin) {
    var b = app.bridge; if (!b || b.t != null || pin || !app.out) return;
    var ch = app.ch, set = AL.node(ch, b.set), m = app.out.M[set.id] || app.out.rootM, l = M.ap(M.inv(m), c[0], c[1]);
    if (!b.pending) {
      var home = b.edit, other = home === 'a' ? 'b' : 'a';
      b.pending = { home: home, p: [l[0], l[1]] }; b.edit = other; show(app, AL.node(ch, b[other])); app.refreshPanel(true);
    } else bridge.commit(app, [l[0], l[1]]);
  };
  bridge.commit = function (app, twin) {
    var b = app.bridge, ch = app.ch, set = AL.node(ch, b.set), pd = b.pending; if (!pd) return;
    var other = pd.home === 'a' ? 'b' : 'a';
    app.edit(function () { var r = record(set, b.a, b.b, true), q = {}; q[pd.home] = [pd.p[0], pd.p[1]]; q[other] = twin || [pd.p[0], pd.p[1]]; r.br.pts.push(q); b.sel = r.br.pts.length - 1; b.pending = null; b.edit = pd.home; show(app, AL.node(ch, b[pd.home])); }, false, 'Match a landmark');
  };
  bridge.cancel = function (app) { var b = app.bridge; if (!b || !b.pending) return; b.edit = b.pending.home; b.pending = null; show(app, AL.node(app.ch, b[b.edit])); app.refreshPanel(true); };

  bridge.render = function (app, pb) {
    var ui = app.ui, ch = app.ch, b = app.bridge, set = AL.node(ch, b.set), A = AL.node(ch, b.a), B = AL.node(ch, b.b);
    if (!set || !A || !B) { app.bridge = null; return AL.cards.render(app); }
    var r = record(set, b.a, b.b, false), pts = r ? r.br.pts : [], ph = $('panelhead');
    var top = b.edit === 'a' ? A : B, under = b.edit === 'a' ? B : A, done = pts.length;
    $('stagebar').innerHTML = ''; ph.innerHTML = '';
    ph.appendChild(h('div', { class: 'ptitle' }, [h('h2', null, ['Match two angles', h('span', { class: 'badge' + (done ? ' ok' : ''), text: done + (done === 1 ? ' landmark' : ' landmarks') })]),
      h('p', { html: '<b style="color:' + COL.a + '">' + A.name + '</b> and <b style="color:' + COL.b + '">' + B.name + '</b>. Mark the same landmarks on both, and each drawing bends toward the other as the head turns.' })]));
    if (b.t != null) app.coach('<b>Previewing the turn.</b> Slide to watch one drawing become the other.');
    else if (b.pending) app.coach('<b>2 of 2.</b> Now tap the <b>same spot</b> on <b>' + top.name + '</b> (the solid drawing). The small dot is where you put it on the other one.');
    else app.coach('<b>1 of 2.</b> Tap a landmark on <b>' + top.name + '</b> (the solid drawing): a spike tip, a horn, an eye corner.');

    if (b.pending) {
      pb.appendChild(ui.note('Landmark <b>' + (pts.length + 1) + '</b> is marked on ' + under.name + '. Tap the same spot on <b>' + top.name + '</b>. Nothing is saved until you do.'));
      pb.appendChild(ui.row([
        ui.chip('It is in the same place', { title: 'This landmark sits at the same spot on both drawings', fn: function () { bridge.commit(app, null); } }),
        ui.chip('Cancel this landmark', { cls: 'quiet', icon: 'trash', fn: function () { bridge.cancel(app); } })
      ]));
    } else {
      pb.appendChild(h('div', { class: 'sl' }, [h('label', { text: 'Solid' }), ui.seg([['a', A.name, 'Show “' + A.name + '” solid: its points can be dragged'], ['b', B.name, 'Show “' + B.name + '” solid: its points can be dragged']], b.edit, function (v) { b.edit = v; b.t = null; show(app, v === 'a' ? A : B); app.refreshPanel(true); }, 'Which drawing is solid'), h('span')]));
      pb.appendChild(ui.slider('Preview the turn', { min: 0, max: 100, value: b.t == null ? 0 : Math.round(b.t * 100), undo: false, set: function (v) { var first = b.t == null; b.t = v / 100; app.bt.x = A.at[0] + (B.at[0] - A.at[0]) * b.t; app.bt.y = A.at[1] + (B.at[1] - A.at[1]) * b.t; if (first) app.refreshPanel(true); },
        fmt: function (v) { return v < 3 ? A.name.slice(0, 6) : v > 97 ? B.name.slice(0, 6) : v + '%'; }, title: 'Scrub from one drawing to the other to see the tween' }));
      if (r) pb.appendChild(ui.slider('They meet', { min: 0, max: 100, value: Math.round((r.br.meet == null ? 0.5 : r.br.meet) * 100), set: function (v) { r.br.meet = v / 100; }, fmt: function (v) { return v < 40 ? 'nearer ' + A.name.slice(0, 5) : v > 60 ? 'nearer ' + B.name.slice(0, 5) : 'half way'; }, title: 'Where the two drawings meet at the moment of the swap. Slide toward the one that should bend less.' }));
      var row = [];
      if (b.t != null) row.push(ui.chip('Back to marking', { icon: 'back', fn: function () { b.t = null; show(app, b.edit === 'a' ? A : B); app.refreshPanel(true); } }));
      if (b.sel >= 0 && pts[b.sel]) row.push(ui.chip('Remove landmark ' + (b.sel + 1), { icon: 'trash', fn: function () { app.edit(function () { pts.splice(b.sel, 1); b.sel = -1; }, false, 'Remove a landmark'); } }));
      if (pts.length) row.push(ui.chip('Clear all', { cls: 'quiet', fn: function () { app.edit(function () { pts.length = 0; b.sel = -1; }, false, 'Clear the landmarks'); } }));
      row.push(ui.chip('Done', { cls: 'go', fn: function () { bridge.close(app); } }));
      pb.appendChild(ui.row(row));
      pb.appendChild(ui.note(pts.length ? 'To move a point, drag it on the <b>solid</b> drawing; switch <b>Solid</b> to move its twin. Three to eight landmarks is usually enough. Points only pull what is near them.' : 'Each landmark is two taps: once on <b>' + top.name + '</b>, then the view swaps and you tap the same spot on <b>' + under.name + '</b>.'));
    }
    app.pinProvider = function () {
      if (b.t != null) return [];
      var out = [], other = b.edit === 'a' ? 'b' : 'a';
      pts.forEach(function (q, i) {
        out.push({ key: b.edit + i, label: String(i + 1), x: q[b.edit][0], y: q[b.edit][1], node: set.id, to: other + i, color: COL[b.edit], set: function (x, y) { q[b.edit][0] = x; q[b.edit][1] = y; }, pick: function () { b.sel = i; } });
        out.push({ key: other + i, ghost: true, x: q[other][0], y: q[other][1], node: set.id, color: COL[other] });
      });
      if (b.pending) out.push({ key: 'pending', ghost: true, x: b.pending.p[0], y: b.pending.p[1], node: set.id, color: COL[b.pending.home], label: String(pts.length + 1) });
      return out;
    };
  };
})(typeof self !== 'undefined' ? self : this);
