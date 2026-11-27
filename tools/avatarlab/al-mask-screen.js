/* AvatarLab: THE FACE MASK SCREEN (job FACE-2; the owner's design, mockup rounds 1 and 2, 11-22; "Build it", 11-27).
 *
 * One screen you stay in, as Face pins: the head on the stage with the mask drawn on it, the panel below. Drag the big
 * handles onto the eyes, nose and mouth ends (or Snap to parts) and the rest of the mask follows; a point you place is
 * yours and stays exactly where you put it (solid); points that follow are hollow; hidden ones dashed; your own added
 * points are diamonds. The strip shows every drawing of the head with its REAL turn, worked out from its points (Adjust if
 * it is wrong): placed by you (cyan), carried and worth a look (pink ring). Each drawing starts from the mask carried to it
 * in 3D; you only fix what reads wrong. The model is al-mask.js; the mask is the head's own landmarks (set.marks['']).
 * Phone: to be specified for the room (FACE-2 card).
 */
(function (root) {
  'use strict';
  var AL = root.AL, h = AL.h, M = AL.M, ms = AL.maskScreen = {};
  var CYAN = '#22d3ee', PINK = '#f472b6', GREY = '#71717a', NS = 'http://www.w3.org/2000/svg';
  function $(id) { return document.getElementById(id); }
  function setOf(app) { var W = app.maskMode; return W ? AL.node(app.ch, W.set) : AL.mask.headSet(app.ch); }

  /** The chip on the head's card (in the "Your face" row, beside Face pins). */
  ms.canOpen = function (app) { return !!(AL.mask && AL.mask.headSet(app.ch)); };
  ms.chip = function (app) {
    var set = AL.mask.headSet(app.ch), on = set && AL.mask.has(set);
    return app.ui.chip(on ? 'Face mask' : 'Put a face mask on', { icon: 'smile',
      title: 'A mask of your face laid on each drawing of the head, so your face moves it at every angle. Most of it is guessed for you.',
      fn: function () { ms.open(app); } });
  };

  ms.open = function (app) {
    var set = AL.mask.headSet(app.ch); if (!set) { app.toast('This character has no turning head yet.'); return; }
    if (app.fpMode && AL.facepins) AL.facepins.close(app);
    var kids = AL.mask.angles(set), cur = AL.node(app.ch, app.force[set.id]);
    if (!cur || kids.indexOf(cur) < 0) cur = AL.node(app.ch, set.pick && set.pick.rest) || kids[0];
    if (!AL.mask.has(set)) app.edit(function () { AL.mask.create(set); }, false, 'Put the face mask on');
    var W = app.maskMode = { set: set.id, angle: cur.id, sel: null, all: false, adding: false, adj: false, hadForce: set.id in app.force, prevForce: app.force[set.id], shapeEdit: app.shapeEdit || null };
    app.shapeEdit = null; app.force[set.id] = cur.id;
    stageOn(app); layerOn(app);
    var ph = $('panelhead'); if (ph) { W.headShown = ph.style.display; ph.style.display = 'none'; }
    app.refreshPanel(true); frame(app);
  };
  /** Close in on the drawing shown, at once (nothing may slide under a finger that is about to place a point). */
  function frame(app) {
    var W = app.maskMode, kid = W && AL.node(app.ch, W.angle), set = setOf(app), m = set && app.out && app.out.M[set.id], b = kid && AL.boundsOf(kid); if (!b) return;
    var xs = [], ys = []; [[b.x0, b.y0], [b.x1, b.y0], [b.x0, b.y1], [b.x1, b.y1]].forEach(function (c) { var w0 = m ? M.ap(m, c[0], c[1]) : c; xs.push(w0[0]); ys.push(w0[1]); });
    var x0 = Math.min.apply(null, xs), x1 = Math.max.apply(null, xs), y0 = Math.min.apply(null, ys), y1 = Math.max.apply(null, ys), w = x1 - x0, hh = y1 - y0;
    app.fitTo({ x0: x0 - w * 0.06, y0: y0 - hh * 0.06, x1: x1 + w * 0.06, y1: y1 + hh * 0.06 }, 8, true); app.userView = true;
  }
  ms.close = function (app) {
    var W = app.maskMode; if (!W) return;
    ms.test(app, false);
    stageOff(app); layerOff(app);
    if (W.hadForce) app.force[W.set] = W.prevForce; else delete app.force[W.set];
    if (W.shapeEdit && !app.shapeEdit) app.shapeEdit = W.shapeEdit;
    var ph = $('panelhead'); if (ph) ph.style.display = W.headShown || '';
    app.maskMode = null; app.refreshPanel(true);
  };
  /** Test: your face drives the mask (in Set up too, where the picture otherwise holds still); the camera follows always while testing. */
  ms.test = function (app, on) {
    var W = app.maskMode; if (!W) return;
    var cam = app.inp.cam;
    if (on && !W.tryHook) {
      W.camMode = cam.mode; W.camWasOn = !!cam.on;
      if (!cam.on && !(AL.intake && AL.intake.hosted)) { app.toast('Starting the face camera\u2026'); app.inp.camStart().then(function () { cam.mode = 'always'; app.toast('Relax and look at the phone: that is your neutral face.'); app.refreshPanel(true); }).catch(function () { app.toast('The face camera could not start here.'); }); }
      else cam.mode = 'always';
      W.tryHook = AL.facepins.tryHook(app); app.hooks.push(W.tryHook);
    } else if (!on && W.tryHook) {
      var k = app.hooks.indexOf(W.tryHook); if (k >= 0) app.hooks.splice(k, 1); W.tryHook = null; AL.facepins.clearStatic(app);
      if (cam.on) cam.mode = W.camWasOn && W.camMode ? W.camMode : 'always';
    }
  };
  /** Show another drawing of the head. */
  ms.goTo = function (app, id) { var W = app.maskMode; if (!W) return; W.angle = id; W.sel = null; W.adding = false; app.force[W.set] = id; app.refreshPanel(true); frame(app); };

  /* ── where things are on the stage ── */
  function toStage(app, set, q) { var m = app.out && app.out.M[set.id]; if (!m || !q) return null; var w = M.ap(m, q[0], q[1]), v = app.view; return { x: w[0] * v.s + v.ox, y: w[1] * v.s + v.oy }; }
  function toSet(app, set, p) { var m = app.out && app.out.M[set.id]; if (!m) return null; var c = app.toChar(p.x, p.y); return M.ap(M.inv(m), c[0], c[1]); }
  /** Each point of the mask on the drawing shown, in stage pixels, from its landmark row (what is drawn there). */
  ms.points = function (app) {
    var W = app.maskMode, set = setOf(app), mk = set && AL.mask.piece(set); if (!W || !mk) return [];
    var row = mk.at[W.angle] || [], see = W.pose ? (W.see == null ? 1 : W.see) : 0;
    return mk.pts.map(function (p, i) {
      var q = row[i], off = W.pose && AL.maskPoses ? AL.maskPoses.offsetOf(set, W.pose, W.angle, p.n) : null;
      if (q && off) q = [q[0] + off[0] * see, q[1] + off[1] * see].concat(q.length > 2 ? [q[2]] : []);
      var at = W.drag && W.drag.i === i ? W.drag : toStage(app, set, q); if (!at) return null;
      var st = AL.marks.stateOf(q), ours = AL.mask.ours(p);
      return { i: i, x: at.x, y: at.y, p: p, state: st, mine: !ours || AL.mask.isMine(set, W.angle, i), ours: ours, big: !!p.handle,
        shown: !ours || !!p.handle || p.mask || W.all || W.sel === i || AL.mask.isMine(set, W.angle, i) || !!(W.pose && p.face) };
    });
  };

  /* ── the mask drawn over the stage (an SVG inside the stage, so it sits exactly on the picture) ── */
  function layerOn(app) {
    var W = app.maskMode, st = $('stage'); if (!st) return;
    W.svg = document.createElementNS(NS, 'svg'); W.svg.setAttribute('aria-hidden', 'true');
    W.svg.style.cssText = 'position:absolute;left:0;top:0;width:100%;height:100%;pointer-events:none;z-index:25;overflow:visible';
    st.appendChild(W.svg);
    W.hook = function () { ms.draw(app); }; app.hooks.push(W.hook);
    W.preHook = function () {                                          // a pose open: the head shows it at See it at (Set up holds still otherwise)
      var bs = app._bs; if (!bs || W.tryHook) return; for (var k in bs) if (k.slice(0, 3) === 'mp.') delete bs[k];
      if (W.pose) bs['mp.' + W.pose] = W.see == null ? 1 : W.see;
    };
    app.hooks.unshift(W.preHook);
  }
  function layerOff(app) { var W = app.maskMode; if (W.capT) clearTimeout(W.capT); [W.hook, W.preHook].forEach(function (hk) { var i = hk ? app.hooks.indexOf(hk) : -1; if (i >= 0) app.hooks.splice(i, 1); }); var bs = app._bs; if (bs) for (var k in bs) if (k.slice(0, 3) === 'mp.') delete bs[k]; if (W.svg && W.svg.parentNode) W.svg.parentNode.removeChild(W.svg); }
  function el(tag, at) { var e = document.createElementNS(NS, tag); for (var k in at) e.setAttribute(k, at[k]); return e; }
  ms.draw = function (app) {
    var W = app.maskMode; if (!W || !W.svg) return;
    var pts = ms.points(app), byKey = {}, svg = W.svg; while (svg.firstChild) svg.removeChild(svg.firstChild);
    pts.forEach(function (q) { if (q && q.p.key) byKey[q.p.key] = q; });
    AL.mask.EDGES.forEach(function (e) {
      var a = byKey[e[0]], b = byKey[e[1]]; if (!a || !b || a.state === 'off' || b.state === 'off') return;
      svg.appendChild(el('line', { x1: a.x, y1: a.y, x2: b.x, y2: b.y, stroke: 'rgba(255,255,255,.38)', 'stroke-width': 1.2, 'stroke-dasharray': a.state === 'behind' || b.state === 'behind' ? '3 4' : '' }));
    });
    pts.forEach(function (q) {
      if (!q || !q.shown) return;
      var r = q.big ? 11 : 6.5, col = q.ours ? CYAN : GREY, g = el('g', { 'data-mask': q.i, 'data-x': q.x.toFixed(2), 'data-y': q.y.toFixed(2), 'data-state': q.state, 'data-mine': q.mine ? 1 : 0 });
      if (W.sel === q.i) g.appendChild(el('circle', { cx: q.x, cy: q.y, r: r + 5, fill: 'none', stroke: '#fff', 'stroke-width': 2 }));
      var fill = q.state === 'off' ? 'rgba(113,113,122,.5)' : q.mine ? col : 'rgba(9,9,11,.55)', stroke = q.state === 'off' ? GREY : col, dash = q.state === 'behind' ? '3 3' : '';
      if (q.p.mask) g.appendChild(el('path', { d: 'M' + q.x + ' ' + (q.y - r - 1) + 'L' + (q.x + r + 1) + ' ' + q.y + 'L' + q.x + ' ' + (q.y + r + 1) + 'L' + (q.x - r - 1) + ' ' + q.y + 'Z', fill: fill, stroke: stroke, 'stroke-width': 2.2, 'stroke-dasharray': dash }));
      else g.appendChild(el('circle', { cx: q.x, cy: q.y, r: r, fill: fill, stroke: stroke, 'stroke-width': q.mine ? 2 : 2.4, 'stroke-dasharray': dash }));
      if (q.state === 'off') g.appendChild(el('line', { x1: q.x - r, y1: q.y + r, x2: q.x + r, y2: q.y - r, stroke: GREY, 'stroke-width': 2 }));
      if (q.big && q.mine && q.state !== 'off') g.appendChild(el('circle', { cx: q.x, cy: q.y, r: 2.6, fill: '#000' }));
      if (W.sel === q.i) { var t = el('text', { x: q.x, y: q.y - r - 9, 'text-anchor': 'middle', fill: '#fff', 'font-size': 12, 'font-weight': 650, 'paint-order': 'stroke', stroke: '#000', 'stroke-width': 3 }); t.textContent = q.p.name || 'Point ' + (q.i + 1); g.appendChild(t); }
      svg.appendChild(g);
    });
  };
  /** The point under a finger (the drawn ones; a big handle first when two overlap). */
  function pointAt(app, p) {
    var best = null, bd = 1e9;
    ms.points(app).forEach(function (q) { if (!q || !q.shown) return; var d = Math.hypot(q.x - p.x, q.y - p.y) - (q.big ? 4 : 0); if (d < (q.big ? 28 : 22) && d < bd) { bd = d; best = q; } });
    return best;
  }

  ms.pointAt = function (app, p) { return pointAt(app, p); };
  /* ── the stage while the mask is open: drag a point, tap to choose one (or to add one), one finger pans, two pinch ── */
  function stageOn(app) {
    var W = app.maskMode, st = $('stage'), ptrs = {}, g = null; if (!st) return;
    function pos(e) { var r = st.getBoundingClientRect(), k = st.clientWidth / r.width; return { x: (e.clientX - r.left) * k, y: (e.clientY - r.top) * k }; }
    function swallow(e) { e.stopImmediatePropagation(); e.stopPropagation(); if (e.cancelable) e.preventDefault(); }
    function down(e) {
      if (e.target && e.target.closest && e.target.closest('button')) return;
      swallow(e); try { st.setPointerCapture(e.pointerId); } catch (x) { /* a made-up event */ }
      var p = pos(e), ids; ptrs[e.pointerId] = p; ids = Object.keys(ptrs);
      if (ids.length === 2) { var a = ptrs[ids[0]], b = ptrs[ids[1]]; g = { kind: 'pinch', d: Math.hypot(a.x - b.x, a.y - b.y) || 1, mx: (a.x + b.x) / 2, my: (a.y + b.y) / 2 }; W.drag = null; return; }
      var hit = W.adding ? null : pointAt(app, p);
      g = hit ? { kind: 'point', i: hit.i, at: p, off: { x: hit.x - p.x, y: hit.y - p.y }, moved: 0 } : { kind: 'pan', at: p, moved: 0 };
    }
    function move(e) {
      if (!ptrs[e.pointerId]) return; swallow(e);
      var p = pos(e), v = app.view; ptrs[e.pointerId] = p; if (!g) return;
      if (g.kind === 'pinch') {
        var ids = Object.keys(ptrs); if (ids.length < 2) return; var a = ptrs[ids[0]], b = ptrs[ids[1]], d = Math.hypot(a.x - b.x, a.y - b.y) || 1, mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2, f = d / g.d;
        v.ox = mx - (g.mx - v.ox) * f; v.oy = my - (g.my - v.oy) * f; v.s *= f; g.d = d; g.mx = mx; g.my = my; app.vt = null; app.userView = true; return;
      }
      g.moved += Math.hypot(p.x - g.at.x, p.y - g.at.y);
      if (g.kind === 'pan' && g.moved > 8) { v.ox += p.x - g.at.x; v.oy += p.y - g.at.y; app.vt = null; app.userView = true; }
      if (g.kind === 'point' && g.moved > 5) W.drag = { i: g.i, x: p.x + g.off.x, y: p.y + g.off.y };
      g.at = p;
    }
    function up(e) {
      if (!ptrs[e.pointerId]) return; swallow(e); delete ptrs[e.pointerId];
      if (Object.keys(ptrs).length) return;
      var k = g, dr = W.drag; g = null; W.drag = null;
      if (!k || k.kind === 'pinch') return;
      var p = pos(e);
      if (k.kind === 'point' && k.moved > 5 && dr) { ms.movePoint(app, k.i, { x: dr.x, y: dr.y }); return; }
      if (k.kind === 'point') { W.sel = W.sel === k.i ? null : k.i; app.refreshPanel(true); return; }
      if (k.moved > 8) return;                                             // that was a pan
      if (W.adding) { ms.addAt(app, p); return; }
      if (W.sel != null) { W.sel = null; app.refreshPanel(true); }
    }
    W.stage = { st: st, down: down, move: move, up: up };
    st.addEventListener('pointerdown', down, true); st.addEventListener('pointermove', move, true);
    st.addEventListener('pointerup', up, true); st.addEventListener('pointercancel', up, true);
  }
  function stageOff(app) {
    var S = app.maskMode.stage; if (!S) return;
    S.st.removeEventListener('pointerdown', S.down, true); S.st.removeEventListener('pointermove', S.move, true);
    S.st.removeEventListener('pointerup', S.up, true); S.st.removeEventListener('pointercancel', S.up, true);
  }

  /* ── edits: each one undo step ── */
  /** Drop point i where the finger let go (stage pixels): yours there, the rest of the mask refits round it. */
  ms.movePoint = function (app, i, p) {
    var W = app.maskMode, set = setOf(app), l = set && toSet(app, set, p); if (!l) return false;
    if (W.pose && AL.maskPoses) {                                      // posing: the point's move in this pose, at full strength
      var mk0 = AL.mask.piece(set), q0 = mk0.at[W.angle] && mk0.at[W.angle][i], see = W.see == null ? 1 : W.see; if (!q0) return false;
      var pz = AL.maskPoses.get(set, W.pose), n0 = mk0.pts[i].n;
      app.edit(function () { AL.maskPoses.move(set, W.pose, W.angle, n0, (l[0] - q0[0]) / Math.max(0.1, see), (l[1] - q0[1]) / Math.max(0.1, see)); }, false, 'Pose ' + (pz ? pz.name : '') + ': move ' + (mk0.pts[i].name || 'a point'));
      W.sel = i; app.refreshPanel(true); return true;
    }
    var name = (AL.mask.piece(set).pts[i] || {}).name || 'a point';
    app.edit(function () { AL.mask.place(set, W.angle, i, Math.round(l[0] * 10) / 10, Math.round(l[1] * 10) / 10); }, false, 'Move ' + name);
    W.sel = i; app.refreshPanel(true); return true;
  };
  ms.addAt = function (app, p) {
    var W = app.maskMode, set = setOf(app), l = set && toSet(app, set, p); if (!l) return -1;
    var i = -1; app.edit(function () { i = AL.mask.addPoint(set, W.angle, Math.round(l[0] * 10) / 10, Math.round(l[1] * 10) / 10); }, false, 'Add a point');
    W.adding = false; W.sel = i; W.naming = true; app.toast('Point added. Give it a name below.'); app.refreshPanel(true);
    return i;
  };

  /* ── the panel ── */
  function stripOf(app, set, sol) {
    var W = app.maskMode;
    return h('div', { style: 'display:flex;gap:6px;overflow-x:auto;padding:2px 0 6px;margin:0 0 4px' }, AL.mask.angles(set).map(function (k) {
      var A = sol.angles[k.id] || {}, you = W.pose && AL.maskPoses ? AL.maskPoses.statusOn(set, W.pose, k.id) === 'you' : A.placed > 0 || A.ok, on = k.id === W.angle;
      var dot = h('span', { style: 'position:absolute;right:3px;top:3px;width:9px;height:9px;border-radius:50%;' + (you ? 'background:' + CYAN : 'border:2px solid ' + PINK + ';box-sizing:border-box') });
      return h('button', { style: 'flex:none;width:66px;padding:3px;border-radius:10px;background:' + (on ? '#18181b' : 'transparent') + ';border:1px solid ' + (on ? CYAN : '#27272a') + ';color:#d4d4d8;position:relative',
        title: k.name + ': ' + (A.turn ? AL.mask.turnWords(A.turn, true) : '') + (you ? ' · placed by you' : ' · carried, worth a look'), onclick: function () { ms.goTo(app, k.id); } }, [
        h('div', { style: 'display:flex;justify-content:center' }, [AL.cards && AL.cards.mini ? AL.cards.mini(k, 44) : null]), dot,
        h('div', { style: 'font-size:10px;line-height:1.15;margin-top:2px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis', text: k.name }),
        h('div', { style: 'font-size:9.5px;line-height:1.2;color:#a1a1aa;min-height:23px', text: A.turn ? AL.mask.turnWords(A.turn) + (A.sure || A.said ? '' : '?') : '' })]);
    }));
  }
  ms.panel = function (app) {
    var W = app.maskMode; if (!W) return false;
    var set = setOf(app); if (!set) { ms.close(app); return true; }
    var pb = $('panelbody'), ui = app.ui, mk = AL.mask.piece(set), kid = AL.node(app.ch, W.angle), sol = AL.mask.solve(set), A = sol.angles[W.angle] || { turn: [0, 0], pts: [] };
    app.pinProvider = null; app.padDraw = null; app.padDraws = [];
    if (app.coach) app.coach(W.adding ? '<b>Add a point:</b> tap the drawing where it goes (a horn tip, a frill edge).' : '<b>Face mask.</b> Drag the big handles onto his eyes, nose and mouth ends; the rest follows. A point you place stays put.');
    pb.innerHTML = '';
    var testing = !!W.tryHook;
    pb.appendChild(h('div', { class: 'rowf', style: 'align-items:center;gap:8px;margin:2px 0 6px' }, [
      h('b', { style: 'font-size:15px', text: 'Face mask' }), h('span', { style: 'flex:1;font-size:12px;color:#a1a1aa', text: kid ? kid.name + ' · ' + AL.mask.turnWords(A.turn) : '' }),
      ui.chip(testing ? 'Stop' : 'Test', { on: testing, icon: testing ? 'stop' : 'play', cls: testing ? '' : 'go', title: testing ? 'Stop testing: the head holds still again' : 'Test the mask: move your face and watch the head follow, on this drawing', fn: function () { ms.test(app, !testing); app.refreshPanel(true); } }),
      ui.chip('Done', { cls: 'go', title: 'Close the face mask and go back to the head', fn: function () { ms.close(app); } })]));
    var gs = ui.slider('All points', { min: 0, max: 3, step: 0.05, value: AL.mask.gain(set), fmt: function (v) { return (Math.round(v * 100) / 100) + '\u00d7'; },
      title: 'How far the whole mask moves for how far your face moves', set: function (v) { AL.mask.setGain(set, v); } });
    gs.style.margin = '0 0 4px'; pb.appendChild(gs);
    pb.appendChild(ui.seg([['mask', 'Mask', 'Put the mask on each drawing'], ['poses', 'Poses', 'Your expressions: make the face, then pose him']], W.tab || 'mask', function (v) {
      if (v === 'poses' && AL.maskPoses && !AL.maskPoses.list(set).length) app.edit(function () { AL.maskPoses.ensure(set); AL.maskPoses.carry(set); }, false, 'Poses');
      W.tab = v; W.pose = null; W.sel = null; W.adding = false; app.refreshPanel(true); }, 'Face mask'));
    pb.appendChild(stripOf(app, set, sol));
    if (W.tab === 'poses' && AL.maskPoses) { posesPanel(app, set, pb); return true; }
    var counts = { you: 0, carried: 0 }; Object.keys(sol.angles).forEach(function (id) { var a = sol.angles[id]; if (a.placed > 0 || a.ok) counts.you++; else counts.carried++; });
    pb.appendChild(ui.note('<b>' + counts.you + '</b> placed by you · <b>' + counts.carried + '</b> carried, worth a look. Every drawing starts from your others, turned to it; you only fix what reads wrong.'));
    pb.appendChild(ui.row([
      ui.chip('Snap to parts', { icon: 'pin', title: 'Put the handles on the slices named Left Eye, Right Eye, Nose, Mouth... (see the naming guide)', fn: function () { var n = 0; app.edit(function () { n = AL.mask.snapAngle(set, W.angle); }, false, 'Snap the mask to parts'); app.toast(n ? n + ' points snapped to named parts.' : 'No slices here with names it knows. See the naming guide.'); app.refreshPanel(true); } }),
      ui.chip('Add a point', { on: W.adding, icon: 'plus', title: 'Add a point of your own (a horn tip): tap the drawing where it goes; it rides with the mask', fn: function () { W.adding = !W.adding; W.sel = null; app.refreshPanel(true); } }),
      ui.chip('Reset this drawing', { title: 'Every place on this drawing back to the guess (your added points stay)', fn: function () { app.edit(function () { AL.mask.resetAngle(set, W.angle); }, false, 'Reset the mask on ' + (kid && kid.name)); app.refreshPanel(true); } }),
      ui.chip(A.ok ? 'Looks right' : 'Looks right?', { on: !!A.ok, cls: A.ok ? '' : 'go', title: 'Mark this drawing as checked by you', fn: function () { app.edit(function () { AL.mask.setOk(set, W.angle, !A.ok); }, false, 'Looks right'); app.refreshPanel(true); } })
    ]));
    pb.appendChild(ui.toggle('Show every point', W.all, function (v) { W.all = v; app.refreshPanel(true); }, 'Brows, forehead, cheeks, lips, jaw. Solid: placed by you (stays put). Hollow: follows the mask',
      'Show the small points too, to fine-tune them. A point you drag becomes yours and stays exactly where you put it'));
    if (W.sel != null && mk && mk.pts[W.sel]) pb.appendChild(pointBox(app, set, mk, W.sel));
    pb.appendChild(turnBox(app, set, A));
    pb.appendChild(ui.row([
      ui.chip('Naming guide', { icon: 'help', title: 'The slice names Snap to parts understands', fn: function () { ms.naming(app); } }),
      ui.chip('Take the mask off', { cls: 'quiet', title: 'Remove the face mask from this head (one Undo puts it back)', fn: function () { app.edit(function () { AL.mask.remove(set); }, false, 'Take the face mask off'); ms.close(app); } })
    ]));
    return true;
  };
  function pointBox(app, set, mk, i) {
    var W = app.maskMode, ui = app.ui, p = mk.pts[i], mine = AL.mask.isMine(set, W.angle, i), row = mk.at[W.angle] || [], st = AL.marks.stateOf(row[i]);
    var name = h('input', { type: 'text', value: p.name || '', 'aria-label': 'Point name', title: 'The point’s name (a creature’s own words: Snout tip, Horn tip)', style: 'flex:1;min-width:0;background:#0c0c0e;border:1px solid #27272a;border-radius:8px;color:#e4e4e7;padding:6px 8px;font:inherit' });
    name.addEventListener('change', function () { var v = name.value; app.edit(function () { AL.mask.rename(set, i, v); }, false, 'Rename a point'); W.naming = false; app.refreshPanel(true); });
    if (W.naming) setTimeout(function () { try { name.focus(); name.select(); } catch (e) { /* */ } }, 0);
    var box = h('div', { style: 'border:1px solid #27272a;border-radius:12px;padding:8px;margin:6px 0' }, [
      h('div', { class: 'rowf', style: 'align-items:center;gap:8px' }, [h('span', { style: 'font-size:12px;color:#a1a1aa', text: 'Point ' + (i + 1) }), name,
        ui.chip('Close', { icon: 'x', title: 'Stop choosing this point', fn: function () { W.sel = null; app.refreshPanel(true); } })]),
      h('div', { style: 'font-size:12px;color:#a1a1aa;margin:4px 0', text: !AL.mask.ours(p) ? 'A landmark made on the landmarks screen: the mask leaves it as it is.' : mine ? 'Placed by you on this drawing: it stays put.' : 'Follows the mask here (drag it to place it yourself).' }),
      ui.seg([['here', 'Here', 'Seen on this drawing'], ['behind', 'Hidden', 'Round the back or covered (the far mouth end behind a muzzle): it pulls nothing here'], ['off', 'Not here', 'Not part of this drawing at all']], st,
        function (v) { app.edit(function () { AL.mask.hide(set, W.angle, i, v); }, false, 'Point: ' + (v === 'behind' ? 'hidden' : v === 'off' ? 'not here' : 'here')); app.refreshPanel(true); }, 'On this drawing'),
      ui.toggle('A big handle', !!p.handle, function (v) { app.edit(function () { AL.mask.setHandle(set, i, v); }, false, 'Big handle'); app.refreshPanel(true); }, 'Always shown, easy to grab: the points you place first')
    ]);
    var acts = [];
    if (mine && AL.mask.ours(p)) acts.push(ui.chip('Follow the mask again', { title: 'Let this point follow the mask on this drawing again', fn: function () { app.edit(function () { AL.mask.follow(set, W.angle, i); }, false, 'Follow the mask again'); app.refreshPanel(true); } }));
    if (p.mask) acts.push(ui.chip('Remove this point', { icon: 'x', title: 'Take this point off every drawing (one Undo puts it back)', fn: function () { app.edit(function () { AL.mask.removePoint(set, i); }, false, 'Remove a point'); W.sel = null; app.refreshPanel(true); } }));
    if (acts.length) box.appendChild(ui.row(acts));
    return box;
  }
  function turnBox(app, set, A) {
    var W = app.maskMode, ui = app.ui, f = AL.mask.data(set), said = f.turn[W.angle];
    var box = h('div', { style: 'border:1px solid #27272a;border-radius:12px;padding:8px;margin:6px 0' }, [
      h('div', { style: 'font-weight:650;font-size:13px;margin-bottom:2px', text: 'How this drawing is turned' }),
      ui.note(said ? 'You set it: <b>' + AL.mask.turnWords(said, true) + '</b>.' : 'From its points it looks <b>' + AL.mask.turnWords(A.turn, true) + '</b>' + (A.sure ? '.' : ' (a rough guess until you place a few points).') +
        ' Its dot on the turn pad stays where you set it: this is only how the drawing really faces, so the mask turns right on it.')
    ]);
    box.appendChild(ui.row([
      ui.chip('Adjust', { on: W.adj, title: 'Say how this drawing is really turned', fn: function () { W.adj = !W.adj; app.refreshPanel(true); } }),
      said ? ui.chip('Use the worked-out turn', { title: 'Work the turn out from the points again', fn: function () { app.edit(function () { AL.mask.setTurn(set, W.angle, null); }, false, 'Use the worked-out turn'); app.refreshPanel(true); } }) : null
    ].filter(Boolean)));
    if (W.adj) {
      var t = said || A.turn;
      box.appendChild(ui.slider('Across', { min: -90, max: 90, step: 1, value: t[0], fmt: function (v) { return AL.mask.turnWords([v, 0]); }, title: 'Toward Left Profile (left) or Right Profile (right)', set: function (v) { AL.mask.setTurn(set, W.angle, [v, (f.turn[W.angle] || t)[1]]); } }));
      box.appendChild(ui.slider('Up and down', { min: -45, max: 45, step: 1, value: t[1], fmt: function (v) { return Math.abs(v) < 1 ? 'level' : Math.abs(v) + '° ' + (v > 0 ? 'up' : 'down'); }, title: 'Looking up or down', set: function (v) { AL.mask.setTurn(set, W.angle, [(f.turn[W.angle] || t)[0], v]); } }));
    }
    return box;
  }
  /* ── poses ── */
  function posesPanel(app, set, pb) {
    var W = app.maskMode, ui = app.ui, MP = AL.maskPoses, f = AL.mask.data(set);
    if (W.pose && MP.get(set, W.pose)) { posePanel(app, set, pb, MP.get(set, W.pose)); return; }
    W.pose = null;
    pb.appendChild(ui.note('Each pose is two steps: <b>make the face</b> at the camera, then <b>pose him</b>: drag his points into your version of it. Made on one drawing, it is carried to the others.'));
    MP.list(set).forEach(function (p) {
      var on = Object.keys(p.at || {}).filter(function (a) { return Object.keys(p.at[a]).length; }).length;
      pb.appendChild(h('div', { class: 'rowf', style: 'align-items:center;gap:8px;padding:6px 4px;border-bottom:1px solid #27272a' }, [
        h('span', { style: 'flex:1;min-width:0' }, [h('b', { text: p.name }), h('div', { style: 'font-size:12px;color:#a1a1aa', text: (p.face ? 'face kept' : 'face not made yet') + ' · ' + (on ? 'posed on ' + on + ' drawing' + (on > 1 ? 's' : '') : 'not posed yet') })]),
        ui.chip('Open', { title: 'Make the face and pose him for ' + p.name, fn: function () { W.pose = p.id; W.see = 1; W.sel = null; app.refreshPanel(true); } })]));
    });
    pb.appendChild(ui.row([ui.chip('Add your own', { icon: 'plus', title: 'A pose of your own (a sneer, a roar)', fn: function () { var id; app.edit(function () { id = MP.add(set); }, false, 'Add a pose'); W.pose = id; W.see = 1; app.refreshPanel(true); } })]));
    pb.appendChild(ui.slider('Directly', { min: 0, max: 1, step: 0.05, value: f.direct == null ? 1 : f.direct, fmt: function (v) { return Math.round(v * 100) + '%'; },
      title: 'What your poses do not explain still follows your face directly, this much', set: function (v) { f.direct = v; } }));
  }
  function posePanel(app, set, pb, p) {
    var W = app.maskMode, ui = app.ui, MP = AL.maskPoses, st = MP.statusOn(set, p.id, W.angle), kid = AL.node(app.ch, W.angle);
    var name = h('input', { type: 'text', value: p.name, 'aria-label': 'Pose name', title: 'The pose’s name', style: 'flex:1;min-width:0;background:#0c0c0e;border:1px solid #27272a;border-radius:8px;color:#e4e4e7;padding:6px 8px;font:inherit;font-weight:650' });
    name.addEventListener('change', function () { var v = name.value; app.edit(function () { MP.rename(set, p.id, v); }, false, 'Rename a pose'); app.refreshPanel(true); });
    pb.appendChild(h('div', { class: 'rowf', style: 'align-items:center;gap:8px;margin:4px 0' }, [ui.chip('All poses', { icon: 'back', title: 'Back to the list of poses', fn: function () { W.pose = null; W.sel = null; app.refreshPanel(true); } }), name]));
    var cam = app.inp.cam;
    pb.appendChild(h('div', { style: 'border:1px solid #27272a;border-radius:12px;padding:8px;margin:6px 0' }, [
      h('div', { style: 'font-weight:650', text: (p.face ? '✓ ' : '1 · ') + 'Make the face' }),
      ui.note(p.cue + ' Hold it and tap <b>Capture</b>. Only where the points of your face are is kept, never a picture.'),
      ui.row([ui.chip(W.capturing ? 'Hold it… ' + W.capturing : p.face ? 'Again' : 'Capture', { icon: 'cam', cls: p.face ? '' : 'go', title: 'Count three, then keep your face as it is then', fn: function () { capture(app, set, p); } })])
    ]));
    pb.appendChild(h('div', { style: 'border:1px solid #27272a;border-radius:12px;padding:8px;margin:6px 0' }, [
      h('div', { style: 'font-weight:650', text: '2 · Pose him' + (kid ? ' on ' + kid.name : '') }),
      ui.note(p.idea + ' Drag any point; the whole head bends as one piece. ' + (st === 'you' ? 'Posed by you on this drawing.' : st === 'carried' ? 'Carried here from another drawing: fix it here if it reads wrong.' : 'Not posed yet.')),
      ui.row([
        p.face ? ui.chip('Start from your face', { title: 'Begin from the face you made, turned to this drawing', fn: function () { app.edit(function () { MP.guess(set, p.id, W.angle); }, false, 'Pose from your face'); app.refreshPanel(true); } }) : null,
        st === 'you' ? ui.chip('Clear here', { icon: 'x', title: 'Take this pose off this drawing (it is carried from the others again)', fn: function () { app.edit(function () { MP.clearAngle(set, p.id, W.angle); }, false, 'Clear the pose here'); app.refreshPanel(true); } }) : null
      ].filter(Boolean)),
      ui.slider('See it at', { min: 0, max: 1, step: 0.05, value: W.see == null ? 1 : W.see, undo: false, fmt: function (v) { return Math.round(v * 100) + '%'; }, title: 'How strong the pose shows here, to check the in-between', set: function (v) { W.see = v; } })
    ]));
    var parts = kid ? MP.swappable(kid) : [];
    if (parts.length) {
      var box = h('div', { style: 'border:1px solid #27272a;border-radius:12px;padding:8px;margin:6px 0' }, [h('div', { style: 'font-weight:650', text: 'Swap a drawing (optional)' }), ui.note('While this pose is strong, a part can show another of its drawings on this drawing (his open mouth).')]);
      var cur = (p.swap && p.swap[W.angle]) || {};
      parts.forEach(function (sp) {
        box.appendChild(h('div', { style: 'font-size:12px;color:#a1a1aa;margin-top:4px', text: sp.name || 'A part' }));
        box.appendChild(ui.row([ui.chip('As painted', { on: !cur[sp.id], fn: function () { app.edit(function () { MP.swapSet(set, p.id, W.angle, sp.id, null); }, false, 'Swap: as painted'); app.refreshPanel(true); } })].concat((sp.kids || []).map(function (k2) {
          return ui.chip(k2.name || k2.shape || 'drawing', { on: cur[sp.id] === k2.id, title: 'Show this drawing while the pose is strong', fn: function () { app.edit(function () { MP.swapSet(set, p.id, W.angle, sp.id, k2.id); }, false, 'Swap a drawing'); app.refreshPanel(true); } });
        }))));
      });
      pb.appendChild(box);
    }
    if (!/^(smile|jaw|brows|puff|pucker)$/.test(p.id)) pb.appendChild(ui.row([ui.chip('Remove this pose', { cls: 'quiet', icon: 'x', fn: function () { app.edit(function () { MP.remove(set, p.id); }, false, 'Remove a pose'); W.pose = null; app.refreshPanel(true); } })]));
    void cam;
  }
  /** Make the face: three, two, one, then keep how the mask's face points moved (the camera on; Test switched on for it). */
  function capture(app, set, p) {
    var W = app.maskMode; if (W.capturing) return;
    if (!W.tryHook) ms.test(app, true);
    var n = 3; W.capturing = n; app.refreshPanel(true);
    var tick = function () {
      if (!app.maskMode || app.maskMode !== W) return;
      n--; if (n > 0) { W.capturing = n; app.refreshPanel(true); W.capT = setTimeout(tick, 700); return; }
      W.capturing = null; var raw = app.inp._mkRaw, ok = false;
      app.edit(function () { ok = AL.maskPoses.capture(set, p.id, raw); }, false, 'Make the face: ' + p.name);
      app.toast(ok ? 'Kept. Now pose him: drag his points.' : 'No face seen. Look at the phone and try again.'); app.refreshPanel(true);
    };
    W.capT = setTimeout(tick, 700);
  }
  ms.capture = function (app, id) { var set = setOf(app), p = AL.maskPoses.get(set, id); if (p) capture(app, set, p); };
  ms.naming = function (app) {
    var rows = AL.mask.NAMING.map(function (r) { return h('div', { style: 'padding:4px 0;border-bottom:1px solid #27272a' }, [h('b', { text: r[0] }), r[1] ? h('span', { style: 'color:#a1a1aa', text: '  ' + r[1] }) : null]); });
    app.sheet('Naming guide', h('div', null, [app.ui.note('Name the slices of each drawing like this and <b>Snap to parts</b> puts the mask on for you. Left and right are the character’s own; capitals and spaces do not matter.')].concat(rows)),
      [{ label: 'Close', fn: function () { app.closeSheet(); } }]);
  };
})(typeof self !== 'undefined' ? self : this);
