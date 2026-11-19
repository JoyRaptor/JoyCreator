/* AvatarLab: bridges. Matching two neighbouring head angles so the swap between them does not jump.
 *
 * On the Head card, small diamonds sit between the selected angle and each of its neighbours. Tapping one
 * opens this: one drawing on the stage, the other ghosted over it in magenta. Tap a landmark (the tip of a
 * crest spike, the corner of an eye) to drop a pair of points, then drag the magenta one onto the same
 * landmark in the ghost. That is the whole job. The solver warps each drawing toward the other as the head
 * turns between them (see AL.bridgeFor in al-solve.js), so the landmarks arrive together.
 *
 * Phone: the pairs are plain data on the turn set; the warp is the puppet mesh engine's MLS, already built.
 */
(function (root) {
  'use strict';
  var AL = root.AL, h = AL.h, M = AL.M;
  function $(id) { return document.getElementById(id); }
  var bridge = AL.bridge = {};

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

  bridge.open = function (app, set, A, B) {
    var ch = app.ch, note = null;
    /* a mirrored angle has no points of its own: match the pair it mirrors, and both sides are done */
    if (A.type === 'mirror' || B.type === 'mirror') {
      var a2 = A.type === 'mirror' ? AL.node(ch, A.of) : (A.at && Math.abs(A.at[0]) < 0.05 ? A : null), b2 = B.type === 'mirror' ? AL.node(ch, B.of) : (B.at && Math.abs(B.at[0]) < 0.05 ? B : null);
      if (a2 && b2 && a2 !== b2) { note = 'These are mirrored. Matching “' + a2.name + '” to “' + b2.name + '” does this side too.'; A = a2; B = b2; }
    }
    var r = record(set, A.id, B.id, false), a = r && r.rev ? B : A, b = r && r.rev ? A : B;
    app.bridge = { set: set.id, a: a.id, b: b.id, edit: a === A ? 'a' : 'b', sel: -1, t: null };
    app.bt.x = A.at[0]; app.bt.y = A.at[1]; app.tab = 'parts'; app.sel = { zone: 'head', feature: null, node: null }; app.resolveSel(); app.userView = false;
    app.refreshPanel(); app.focusSel(); if (note) app.toast(note);
  };
  bridge.close = function (app) { var b = app.bridge; app.bridge = null; if (b) { var a = AL.node(app.ch, b.a); if (a && a.at) { app.bt.x = a.at[0]; app.bt.y = a.at[1]; } } app.resolveSel(); app.refreshPanel(); };

  /** A tap on the stage while matching: drop a new pair there. */
  bridge.tap = function (app, c, pin) {
    var b = app.bridge; if (!b || b.t != null || pin) return;
    var set = AL.node(app.ch, b.set), m = app.out.M[set.id] || app.out.rootM, l = M.ap(M.inv(m), c[0], c[1]);
    app.edit(function () { var r = record(set, b.a, b.b, true); r.br.pts.push({ a: [l[0], l[1]], b: [l[0], l[1]] }); b.sel = r.br.pts.length - 1; }, false, 'Add a match point');
  };

  bridge.render = function (app, pb) {
    var ui = app.ui, ch = app.ch, b = app.bridge, set = AL.node(ch, b.set), A = AL.node(ch, b.a), B = AL.node(ch, b.b);
    if (!set || !A || !B) { app.bridge = null; return AL.cards.render(app); }
    var r = record(set, b.a, b.b, false), pts = r ? r.br.pts : [], ph = $('panelhead'), top = b.edit === 'a' ? A : B, under = b.edit === 'a' ? B : A;
    $('stagebar').innerHTML = ''; ph.innerHTML = '';
    ph.appendChild(h('div', { class: 'ptitle' }, [h('h2', null, ['Match two angles', h('span', { class: 'badge' + (pts.length ? ' ok' : ''), text: pts.length + (pts.length === 1 ? ' point' : ' points') })]),
      h('p', { html: '<b>' + A.name + '</b> and <b>' + B.name + '</b>. Mark the same landmarks on both and each drawing bends toward the other as the head turns, so the swap does not jump.' })]));
    app.coach(b.t != null ? '<b>Previewing the turn.</b> Slide to watch one drawing become the other.' : '<b>Tap a landmark</b> on the drawing to drop a pair of points. Drag the <b style="color:#f43f8e">magenta</b> one onto the same landmark in the ghost.');
    pb.appendChild(h('div', { class: 'sl' }, [h('label', { text: 'On top' }), ui.seg([['a', A.name, 'Show “' + A.name + '” solid, the other as a ghost'], ['b', B.name, 'Show “' + B.name + '” solid, the other as a ghost']], b.edit, function (v) { b.edit = v; b.t = null; var k = v === 'a' ? A : B; app.bt.x = k.at[0]; app.bt.y = k.at[1]; app.refreshPanel(true); }, 'Which drawing is solid'), h('span')]));
    pb.appendChild(ui.slider('Preview the turn', { min: 0, max: 100, value: b.t == null ? 0 : Math.round(b.t * 100), undo: false, set: function (v) { var first = b.t == null; b.t = v / 100; app.bt.x = A.at[0] + (B.at[0] - A.at[0]) * b.t; app.bt.y = A.at[1] + (B.at[1] - A.at[1]) * b.t; if (first) app.refreshPanel(true); },
      fmt: function (v) { return v < 3 ? A.name.slice(0, 6) : v > 97 ? B.name.slice(0, 6) : v + '%'; }, title: 'Scrub from one drawing to the other to see the tween' }));
    if (r) pb.appendChild(ui.slider('They meet', { min: 0, max: 100, value: Math.round((r.br.meet == null ? 0.5 : r.br.meet) * 100), set: function (v) { r.br.meet = v / 100; }, fmt: function (v) { return v < 40 ? 'nearer ' + A.name.slice(0, 5) : v > 60 ? 'nearer ' + B.name.slice(0, 5) : 'half way'; }, title: 'Where the two drawings meet at the moment of the swap. Slide toward the one that should bend less.' }));
    var row = [];
    if (b.t != null) row.push(ui.chip('Back to matching', { icon: 'back', fn: function () { b.t = null; var k = b.edit === 'a' ? A : B; app.bt.x = k.at[0]; app.bt.y = k.at[1]; app.refreshPanel(true); } }));
    if (b.sel >= 0 && pts[b.sel]) row.push(ui.chip('Remove point ' + (b.sel + 1), { icon: 'trash', fn: function () { app.edit(function () { pts.splice(b.sel, 1); b.sel = -1; }, false, 'Remove a match point'); } }));
    if (pts.length) row.push(ui.chip('Clear all', { cls: 'quiet', fn: function () { app.edit(function () { pts.length = 0; b.sel = -1; }, false, 'Clear the match points'); } }));
    row.push(ui.chip('Done', { cls: 'go', fn: function () { bridge.close(app); } }));
    pb.appendChild(ui.row(row));
    pb.appendChild(ui.note(pts.length ? 'Three to eight points on things that should line up (horn tips, crest spikes, the chin, the eye corners) is usually enough. Points only pull what is near them.' : 'Nothing matched yet, so the swap is a plain cross-fade. <b>' + top.name + '</b> is solid; <b>' + under.name + '</b> is the magenta ghost.'));
    app.pinProvider = function () {
      if (b.t != null || !r) return [];
      var out = [];
      pts.forEach(function (q, i) {
        ['a', 'b'].forEach(function (sd) {
          var mine = sd === b.edit;
          out.push({ key: sd + i, label: mine ? String(i + 1) : null, x: q[sd][0], y: q[sd][1], node: set.id, to: sd === 'a' ? 'b' + i : null, color: mine ? '#22d3ee' : '#f43f8e',
            set: function (x, y) { q[sd][0] = x; q[sd][1] = y; }, pick: function () { b.sel = i; } });
        });
      });
      return out;
    };
  };
})(typeof self !== 'undefined' ? self : this);
