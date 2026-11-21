/* AvatarLab: THE STACK. One control for "in front or behind".
 *
 * A row of small pictures of the parts, in the order they are really drawn: the back on the left, the front on the right, each one
 * overlapping the one behind it. The part being worked on is cyan. Step it back or forward, or tap another part to step past that
 * one, and the picture changes with it.
 *
 * What a step writes depends on where you are, and the section's title says which:
 *   in a stance            "in this stance, this part is drawn just behind / in front of that one"  (poses[].d[id].z)
 *   a whole part           the same sentence, always                                              (node.zo)
 *   a layer inside a part  the layer moves among the layers beside it                             (the order of the folder)
 * A limb can also come round: its near end stays behind and its hand or foot comes in front (z.tip).
 *
 * The row is read from the picture's own draw list every frame, so it cannot disagree with what is on the stage.
 * Phone: the same strip; it is a horizontally scrolling row of 50 dp tiles.
 */
(function (root) {
  'use strict';
  var AL = root.AL, stack = AL.stack = {};
  function isLimb(n) { return !!(n && n.type === 'set' && n.pick && n.pick.by === 'reach' && n.pick.hand); }
  /* the picture on a tile: of a part with several drawings, the one on show (not all of them on top of each other) */
  function face(app, u) { if (u.type !== 'set') return u; var k = AL.node(app.ch, app.out && app.out.picks && app.out.picks[u.id]) || (u.pick && AL.node(app.ch, u.pick.rest)) || (u.kids || [])[0]; return k && (u.kids || []).indexOf(k) >= 0 ? k : u; }
  function endOf(n) { return /^leg/.test(n.role || '') || (n.pick && n.pick.hand && n.pick.hand.charAt(0) === 'F') ? 'foot' : 'hand'; }

  /** The parts this one is stacked against, as runs of the draw list from back to front. */
  stack.read = function (app, n) {
    var ch = app.ch, ix = AL.ix(ch), out = app.out, units = {}, spine = {};
    function mark(q) { if (!q) return; units[q.id] = q; var p = ix.p[q.id]; while (p) { spine[p.id] = 1; p = ix.p[p.id]; } }
    AL.ZONES.forEach(function (z) { mark(app.zoneNode(z.id)); }); AL.findAll(ch.root, isLimb).forEach(mark);
    function owner(id) { var q = ix.n[id], top = null; while (q) { if (units[q.id]) return q; if (!spine[q.id]) top = q; q = ix.p[q.id]; } return top; }
    var whole = owner(n.id) === n, par = ix.p[n.id] || null;
    function own(id) { if (whole) return owner(id); var q = ix.n[id]; while (q && ix.p[q.id] !== par) q = ix.p[q.id]; return q || null; }
    var runs = [];
    (out ? out.items : []).forEach(function (it) { if (it.ghost) return; var u = own(it.id); if (!u) return; var last = runs[runs.length - 1]; if (last && last.u === u) last.n++; else runs.push({ u: u, n: 1 }); });
    return { whole: whole, par: par, runs: runs };
  };

  /** Where the order of this part is kept right now. */
  function getZ(app, n) { var p = AL.fit.pose(app); if (p) { var d = p.d && p.d[n.id]; return d && d.z ? d.z : null; } return n.zo || null; }
  function setZ(app, n, z, label) {
    var p = AL.fit.pose(app);
    app.liftArt = false;                                       // show it as it really sits, or the step could not be seen
    app.edit(function () {
      if (p) { var e = (p.d = p.d || {})[n.id] || (p.d[n.id] = {}); if (z) e.z = z; else { delete e.z; if (!e.auto && !e.x && !e.y && !e.rot && (e.sc == null || e.sc === 1) && !e.orbit) delete p.d[n.id]; } }
      else if (z) n.zo = z; else delete n.zo;
    }, false, label);
  }

  stack.section = function (app, pb, n, opts) {
    opts = opts || {};
    var ui = app.ui, h = AL.h, ch = app.ch, pose = AL.fit.pose(app), R = stack.read(app, n), name = AL.cards.nameOf(app, n);
    if (!app.out) { clearTimeout(stack._late); stack._late = setTimeout(function () { if (app.out) app.refreshPanel(true); }, 80); return false; }      // the very first card is drawn before the first picture: come back once there is one
    var others = R.runs.filter(function (r) { return r.u !== n; }).length;
    if (!others) return false;                                  // nothing to be in front of
    var layerMode = !R.whole && !pose, z = layerMode ? null : getZ(app, n), limb = isLimb(n), end = limb ? endOf(n) : '';
    var angle = !pose && AL.ancestorWith(ch, n.id, function (a) { return !!a.at; });
    pb.appendChild(ui.lab('In front or behind' + (pose ? ', in the stance “' + pose.name + '”' : angle ? ', at this angle' : ''), 'fit'));
    var strip = h('div', { class: 'stk' }), sig = null, cur = R;

    /* one step past a part: behind it if it is further back, in front of it if it is further forward */
    function past(t, back) {
      var tn = AL.cards.nameOf(app, t), lab = (back ? 'Behind ' : 'In front of ') + tn + (pose ? ', in ' + pose.name : '');
      if (layerMode) { app.edit(function () { var k = R.par.kids.filter(function (x) { return x !== n; }), i = k.indexOf(t); k.splice(back ? i + 1 : i, 0, n); R.par.kids = k; }, false, lab); return; }
      var nz = { how: back ? 'under' : 'over', of: t.id };
      if (z && z.at != null) nz.at = z.at; if (back && z && z.tip != null) nz.tip = z.tip;
      setZ(app, n, nz, lab);
    }
    function mine(r) { for (var i = 0; i < r.runs.length; i++) if (r.runs[i].u === n) return i; return -1; }
    function step(dir) {
      var r = stack.read(app, n), i = mine(r); if (i < 0) return;
      if (dir > 0) { while (i + 1 < r.runs.length && r.runs[i + 1].u === n) i++; }      // (a part in two pieces steps as its near end)
      var j = i + dir; while (j >= 0 && j < r.runs.length && r.runs[j].u === n) j += dir;
      if (j < 0 || j >= r.runs.length) return;
      past(r.runs[j].u, dir < 0);
    }
    /* hold the cyan tile and drag it along the row: it lands just behind the first part to its right */
    function grab(el) {
      el.style.touchAction = 'none';
      el.onpointerdown = function (ev) {
        var x0 = ev.clientX, moved = false; try { el.setPointerCapture(ev.pointerId); } catch (e) { }
        el.onpointermove = function (e) { var dx = e.clientX - x0; if (Math.abs(dx) > 5) moved = true; if (moved) { el.style.transform = 'translateX(' + dx + 'px) scale(1.08)'; el.style.zIndex = 5; } };
        el.onpointerup = el.onpointercancel = function (e) {
          el.onpointermove = el.onpointerup = el.onpointercancel = null; el.style.transform = ''; el.style.zIndex = '';
          if (!moved) return;
          var r0 = el.getBoundingClientRect(), cx = r0.left + r0.width / 2 + (e.clientX - x0), all = Array.prototype.slice.call(strip.children), others = all.filter(function (c) { return !c.classList.contains('me'); }), tgt = null;
          for (var i = 0; i < others.length; i++) { var r = others[i].getBoundingClientRect(); if (r.left + r.width / 2 > cx) { tgt = others[i]; break; } }
          var nextEl = all[all.indexOf(el) + 1]; while (nextEl && nextEl.classList.contains('me')) nextEl = all[all.indexOf(nextEl) + 1];
          if (tgt === (nextEl || null) && all.filter(function (c) { return c.classList.contains('me'); }).length === 1) return;      // dropped where it was
          if (tgt) past(tgt._u, true); else if (others.length) past(others[others.length - 1]._u, false);
        };
      };
    }
    function fill(r) {
      cur = r; strip.innerHTML = '';
      var seen = {}, count = {}; r.runs.forEach(function (q) { count[q.u.id] = (count[q.u.id] || 0) + 1; });
      var first = mine(r);
      r.runs.forEach(function (q, i) {
        var me = q.u === n, two = count[q.u.id] > 1, k = seen[q.u.id] = (seen[q.u.id] || 0) + 1, nm = AL.cards.nameOf(app, q.u);
        var part = two && isLimb(q.u) ? (k === 1 ? ' · top' : ' · ' + endOf(q.u)) : '';
        var title = me ? nm + part + ': the part you are placing. Hold and drag it along the row.' : (first < 0 || i < first ? 'Put ' + name + ' just behind ' + nm : 'Put ' + name + ' just in front of ' + nm);
        var el = h('button', { class: (me ? 'me' : '') + (two ? ' half' : ''), title: title, 'aria-label': title, onclick: me ? null : function () { past(q.u, first < 0 || i < first); } }, [AL.cards.mini(face(app, q.u), 34), h('span', { text: nm + part })]);
        el._u = q.u; if (me) grab(el); strip.appendChild(el);
      });
      var mineEl = strip.querySelector('.me'); if (mineEl && strip.scrollWidth > strip.clientWidth) strip.scrollLeft = mineEl.offsetLeft - strip.clientWidth / 2 + 25;
    }
    function draw() { var r = stack.read(app, n), s = r.runs.map(function (q) { return q.u.id; }).join(','); if (s !== sig) { sig = s; fill(r); } }
    draw(); (app.padDraws = app.padDraws || []).push(draw);
    pb.appendChild(h('div', { class: 'stkw' }, [h('span', { class: 'end', text: 'Back' }), strip, h('span', { class: 'end', text: 'Front' })]));

    var row = [
      ui.chip('Back one', { icon: 'back', title: 'One step further back: behind the part on its left', fn: function () { step(-1); } }),
      ui.chip('Forward one', { icon: 'fwd', title: 'One step further forward: in front of the part on its right', fn: function () { step(1); } })
    ];
    if (!layerMode) {
      row.push(ui.chip('Very back', { on: !!(z && z.how === 'back'), title: 'Behind everything', fn: function () { setZ(app, n, z && z.how === 'back' ? null : { how: 'back' }, 'Behind everything'); } }));
      row.push(ui.chip('Very front', { on: !!(z && z.how === 'front'), title: 'In front of everything', fn: function () { setZ(app, n, z && z.how === 'front' ? null : { how: 'front' }, 'In front of everything'); } }));
      if (z) row.push(ui.chip(pose ? 'As when standing' : 'Its own place', { cls: 'quiet', title: pose ? 'Forget the order for this stance: the same order as when it stands' : 'Forget this: back to where the file has it', fn: function () { setZ(app, n, null, 'Its own place'); } }));
    }
    pb.appendChild(ui.row(row));
    var tn = z && z.of ? AL.node(ch, z.of) : null;
    if (limb && z && z.how === 'under' && tn) {
      var tnm = AL.cards.nameOf(app, tn);
      pb.appendChild(ui.toggle('The ' + end + ' comes in front', z.tip != null, function (v) { app.edit(function () { if (v) z.tip = 0.5; else delete z.tip; }, false, 'The ' + end + ' in front of ' + tnm); }, 'The near end stays behind ' + tnm + ' and the ' + end + ' passes in front of it, like an arm coming round'));
      if (z.tip != null) pb.appendChild(ui.slider('Comes in front from', { min: 10, max: 90, value: Math.round(z.tip * 100), set: function (v) { z.tip = v / 100; }, fmt: function (v) { return v < 40 ? 'near the top' : v > 60 ? 'near the ' + end : 'the middle'; }, title: 'Where along it the drawing goes from behind to in front. Put it where the limb is clear of the other part.' }));
    }
    if (pose && z) pb.appendChild(ui.slider('Changes over at', { min: 5, max: 95, value: Math.round((z.at == null ? 0.5 : z.at) * 100), set: function (v) { z.at = v / 100; }, fmt: function (v) { return v + '% of the way in'; }, title: 'How far into the stance the order changes. Pick the moment the two parts are not touching and it cannot be seen.' }));
    if (!opts.quiet) pb.appendChild(ui.note('Left is the back, right is the front. <b>Drag the cyan one</b> along the row, or tap a part to step past it' + (pose ? '. Only while it is in this stance.' : layerMode ? '.' : '. It stays like this.')));
    return true;
  };
})(typeof window !== 'undefined' ? window : globalThis);
