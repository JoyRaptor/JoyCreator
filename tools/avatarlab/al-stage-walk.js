/* AvatarLab: WALKING ACROSS THE STAGE. In a scene, dragging the body walks the character across the scene and off either
 * side, so an entrance can come from out of the picture.
 *
 * Alone on the plain stage a character walks in the room its own view keeps for it. In a scene it stands at a PLACE on the
 * stage (the scene item's x, y and size: what Arrange sets) and its walk starts from there. The walker itself (al-walk.js) is
 * the same one: the same planted feet, the same turn-round, the same four-legged walk. What a scene adds is here:
 *
 *   HOW FAR      a third of the stage's width past either side of the stage, and no further (walker.lim). The stage, not the
 *                frame: the frame is only a shape, and changing it must move nothing. (Up and down the floor is not walking yet.)
 *   A FINGER     cannot leave the glass. So a finger holding the body at the side edge of the glass means "keep going": the
 *                character walks on, out of the picture. Bring the finger back in and it comes back to the finger.
 *   THE JOYSTICK and its dashes reach the same limits (al-stick.js and al-stick-moves.js ask walker.lim).
 *   WAITING      a character that cannot be seen (it walked out, it was placed outside, or the shot is close on someone else)
 *                has a small tab at that edge of the glass, at the height of its floor. Drag the tab and it walks in after the
 *                finger; a tap brings it to the edge. That is how an entrance is performed with a finger.
 *   THE OTHERS   watch where the one in hand IS, not where it started (scene.draw asks stageWalk.at).
 *   PUT DOWN     taking another character in hand lets go of this one: it stops where it is (scene.hand asks stageWalk.letGo).
 *
 * A walk is kept in the character's own drawing units, measured from its place (the take's rootX, feet and walking hands), so
 * the body's proportions never change and the same take plays in any frame shape and any shot with the feet on the same
 * spots of the stage. (Resize the character in Arrange afterwards and its recorded walk is that much shorter or longer.)
 *
 * Phone: the limits are two numbers on the walker; the edge rule lives in the stage's pointerInput; the tabs are a small
 * Compose Box at each edge of the stage. Nothing here is a second walker.
 */
(function (root) {
  'use strict';
  var AL = root.AL, app = AL.app, scene = AL.scene, h = AL.h, W = scene.W, sw = AL.stageWalk = {}, NS = 'http://www.w3.org/2000/svg';
  function $(id) { return document.getElementById(id); }
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  sw.BEYOND = 1 / 3;        // how far past either side of the stage it may walk, as a part of the stage's width
  sw.EDGE = 26;             // px: a finger holding the body this near the side edge of the glass keeps it walking
  sw.PACE = 4;              // strides a second it walks on at with the finger just inside that edge (twice that with the finger right on it)
  function live(R) { return !!R && !R.dive && (R.stage === 'perform' || R.stage === 'motion'); }
  function width(ch) { var b = AL.boundsOf(ch.root) || { x0: 0, x1: ch.w }; return Math.max(20, b.x1 - b.x0); }

  /* ── how far ── */
  /** The walker's room on the stage for a cast member standing at its place T (scene.place): [furthest left, furthest right], in its own drawing units from its place. */
  sw.reach = function (it, T) { var out = W * sw.BEYOND, k = Math.max(1e-6, T.k); return [Math.min(0, (-out - it.x) / k), Math.max(0, (W + out - it.x) / k)]; };
  /** Where a cast member stands on the stage right now (stage units, across): its place, and however far it has walked or its take has taken it. */
  sw.at = function (R, it) { var a = R.actors[it.id], r = a && a.inp && a.inp.S && a.inp.S.root; return it.x + (live(R) && a && a.T && r ? r.x * a.T.k : 0); };
  /** A performer is put down (another is taken in hand): no finger is on it any more, and it stops where it is. */
  sw.letGo = function (inp) {
    var w = inp && inp.walker; if (!w) return;
    w.release(); w.tx = w.x; clearTimeout(inp._mv);
    for (var k in inp.held) if (/^(body|head|hand|foot)/.test(k)) inp.held[k] = false;      // (every hand and foot, the other feet of something with many legs too)
    if (pull && pull.inp === inp) pull = null;
    if (app.dropFingers) app.dropFingers();                                                  // a finger still down on it moves nobody from here on (al-app.js)
  };

  /* ── taking the body of a small character ──
     A finger within 30 px of a hand or a foot takes that hand or foot (al-app.js). In a scene at phone size the whole character is
     only some 90 px tall, so those circles cover all of it and the body could never be taken. So in a scene the circles are never
     wider than a part of the character's own height as it is drawn. (Touching an arm or a leg itself still takes its hand or foot.) */
  sw.TIP = 0.13;            // of the character's drawn height
  /** How much of the usual reach round a hand, a foot or a pin a finger has on the character in hand: 1 alone on the plain stage. */
  sw.near = function (app) {
    if (!scene.on(app) || !app.ch || !app.view) return 1;
    var b = AL.boundsOf(app.ch.root) || { y0: 0, y1: app.ch.h }, px = Math.max(1, (b.y1 - b.y0) * app.view.s);
    return Math.min(1, px * sw.TIP / 30);
  };

  /* ── a finger at the edge of the glass ── */
  var ptr = {}, pull = null;
  function pos(e) { var st = $('stage'), r = st.getBoundingClientRect(), k = st.clientWidth / (r.width || 1); return { x: (e.clientX - r.left) * k, y: (e.clientY - r.top) * k }; }
  /** Which finger has the body: the one that went down exactly where the walker was grabbed (the joystick and the gestures grab at nothing). */
  function fingerOf(g0) { for (var id in ptr) { var c = ptr[id].c; if (c && Math.abs(c[0] - g0.x) < 1e-6 && Math.abs(c[1] - g0.y) < 1e-6) return id; } return null; }
  /** Once a frame: the finger that holds the body is at the side edge of the glass, so the body walks on that way; back inside, it comes back to the finger. Returns how far the target was moved. */
  sw.push = function (app, dt) {
    var inp = app.inp, w = inp && inp.walker, g0 = w && w.g0;
    if (!g0 || !w.held || !w.lim || !w.travels()) return 0;
    if (g0.pid === undefined) g0.pid = fingerOf(g0);
    var p = g0.pid == null ? null : ptr[g0.pid], gw = $('stage').clientWidth, side = 0, deep = 0;
    if (p) { if (p.x > gw - sw.EDGE) { side = 1; deep = (p.x - (gw - sw.EDGE)) / sw.EDGE; } else if (p.x < sw.EDGE) { side = -1; deep = (sw.EDGE - p.x) / sw.EDGE; } }
    if (g0.tab) { if (!side) g0.tab = false; side = 0; }                          // (a finger that came down on a waiting tab starts at the edge: it must leave the edge before the edge means "keep going")
    var cfg = w.cfg(), unit = cfg ? Math.max(8, cfg.stride || 60) : width(app.ch) * 0.25, have = g0.push || 0, d;
    if (side) d = side * unit * sw.PACE * (1 + clamp(deep, 0, 1)) * dt;
    else if (have) d = -(have > 0 ? 1 : -1) * Math.min(Math.abs(have), unit * sw.PACE * 2 * dt);
    else return 0;
    d = clamp(w.tx + d, w.lim[0], w.lim[1]) - w.tx;
    g0.tx += d; w.tx += d; g0.push = have + d;
    return d;
  };

  /* ── waiting out of sight: a tab at the edge of the glass ── */
  document.head.appendChild(h('style', { text: [
    '#waittabs{position:absolute;inset:0;pointer-events:none;z-index:2}',
    '.waittab{position:absolute;height:32px;padding:0 7px;display:flex;align-items:center;gap:2px;pointer-events:auto;cursor:grab;touch-action:none;-webkit-touch-callout:none;background:var(--veil);backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px);border:1px solid rgba(255,255,255,.08);color:var(--dim)}',
    '.waittab:hover{color:var(--ink);border-color:rgba(255,255,255,.16)}',
    '.waittab.l{left:0;border-left:0;border-radius:0 16px 16px 0} .waittab.r{right:0;border-right:0;border-radius:16px 0 0 16px;flex-direction:row-reverse}',
    '.waittab img{width:24px;height:24px;object-fit:contain;pointer-events:none}',
    '.waittab svg{width:11px;height:11px;fill:none;stroke:currentColor;stroke-width:2.2;stroke-linecap:round;stroke-linejoin:round;pointer-events:none}'
  ].join('\n') }));
  function chevron(left) { var s = document.createElementNS(NS, 'svg'); s.setAttribute('viewBox', '0 0 12 12'); s.setAttribute('aria-hidden', 'true'); var p = document.createElementNS(NS, 'path'); p.setAttribute('d', left ? 'M8 2L4 6l4 4' : 'M4 2l4 4-4 4'); s.appendChild(p); return s; }
  var tabs = {};
  function tabFor(id) {
    var t = tabs[id]; if (t) return t;
    var box = $('waittabs') || $('stage').appendChild(h('div', { id: 'waittabs' }));
    t = tabs[id] = { el: h('button', { class: 'waittab', type: 'button' }), side: '', pic: null, name: null, walks: null };
    t.el.addEventListener('pointerdown', function (e) { tabDown(e, id); });
    t.el.addEventListener('click', function (e) { if (e.detail === 0) tabKey(id); });      // (the keyboard: Enter or Space on the tab)
    box.appendChild(t.el); return t;
  }
  /** Which cast members cannot be seen on the glass, and at which side: { id: 'l' | 'r' }. */
  sw.waiting = function (R) {
    var out = {}, V = R.V, gw = $('stage').clientWidth; if (!live(R) || !V) return out;
    scene.cast(R).forEach(function (it) { var b = scene.boxOf(R, it); if (!b) return; if (V.ox + b[2] * V.s < 3) out[it.id] = 'l'; else if (V.ox + b[0] * V.s > gw - 3) out[it.id] = 'r'; });
    return out;
  };
  /** Once a frame: a tab for every cast member that cannot be seen, at its side of the glass and the height of its floor. */
  sw.tabs = function (app) {
    var R = app.scene, on = scene.on(app) && live(R), wait = on ? sw.waiting(R) : {}, id, used = { l: [], r: [] };
    for (id in tabs) if (!wait[id]) { tabs[id].el.remove(); delete tabs[id]; }
    if (!on) return;
    var st = $('stage'), gh = st.clientHeight, A = AL.frame.area(app), V = R.V;
    scene.cast(R).forEach(function (it) {
      var side = wait[it.id]; if (!side) return;
      var a = R.actors[it.id], t = tabFor(it.id), walks = !!a.inp.walker.travels(), y = clamp(V.oy + it.y * V.s - 38, A.y + 2, gh - 40), u = used[side], i, hit = true;
      while (hit) { hit = false; for (i = 0; i < u.length; i++) if (Math.abs(u[i] - y) < 36) { y = u[i] - 36; hit = true; } }      // two waiting on the same side: one above the other
      u.push(y);
      if (t.side !== side || t.pic !== a.pic || t.name !== it.name || t.walks !== walks) {
        t.side = side; t.pic = a.pic; t.name = it.name; t.walks = walks; t.el.className = 'waittab ' + side; t.el.textContent = '';
        t.el.appendChild(chevron(side === 'l')); if (a.pic) t.el.appendChild(h('img', { src: a.pic, alt: '' }));
        var say = it.name + ' is waiting out of the picture. ' + (walks ? 'Drag to walk it in, or tap to bring it to the edge.' : 'Tap to take it in hand.');
        t.el.title = say; t.el.setAttribute('aria-label', say);
      }
      t.el.style.top = Math.round(y) + 'px';
    });
  };
  /* a finger on a tab: that character is in hand, and its body goes to the finger (it walks there: it cannot be quicker than its legs) */
  function tabDown(e, id) {
    var R = app.scene; if (!scene.on(app) || !live(R) || !R.actors[id]) return;
    e.preventDefault(); e.stopPropagation();
    if (R.focus !== id) scene.hand(app, id);
    var st = $('stage'), inp = app.inp, w = inp.walker, a = R.actors[id], p = pos(e), c = app.toChar(p.x, p.y);
    if (!w.travels() || !a.T) return;
    try { st.setPointerCapture(e.pointerId); } catch (x) { }
    ptr[e.pointerId] = { x: p.x, y: p.y, c: null };
    inp.held.body = true; w.held = true; w.fallV = 0;
    w.g0 = { x: c[0], y: c[1], tx: c[0] - a.T.ax, ty: w.ty, pid: String(e.pointerId), tab: true }; w.tx = w.g0.tx;
    pull = { id: e.pointerId, inp: inp, g0: w.g0 };
  }
  function tabKey(id) { var R = app.scene, a = R && R.actors[id], side = a && sw.waiting(R)[id]; if (!side) return; if (R.focus !== id) scene.hand(app, id); var w = app.inp.walker, V = R.V, it = scene.item(R, id); if (w.travels() && it) w.tx = ((side === 'l' ? 40 : $('stage').clientWidth - 40) - V.ox) / V.s / a.T.k - it.x / a.T.k; }
  function pullEnd() { if (!pull) return; var inp = pull.inp, w = inp.walker; if (w.g0 === pull.g0) w.release(); inp.held.body = false; pull = null; }
  function wire() {
    var st = $('stage');
    st.addEventListener('pointerdown', function (e) { var p = pos(e); ptr[e.pointerId] = { x: p.x, y: p.y, c: app.view ? app.toChar(p.x, p.y) : null }; }, true);
    st.addEventListener('pointermove', function (e) {
      var q = ptr[e.pointerId]; if (!q) return; var p = pos(e); q.x = p.x; q.y = p.y;
      if (pull && pull.id === e.pointerId) { var w = pull.inp.walker; if (pull.inp === app.inp && w.g0 === pull.g0) w.drag([app.toChar(p.x, p.y)[0], pull.g0.y]); else pullEnd(); }      // (a tab only walks: up and down are the body's own to ask for)
    }, true);
    ['pointerup', 'pointercancel'].forEach(function (n) { st.addEventListener(n, function (e) { delete ptr[e.pointerId]; if (pull && pull.id === e.pointerId) pullEnd(); }, true); });
  }
  wire();
  app.hooks.push(function (dt) { if (scene.on(app) && live(app.scene)) sw.push(app, dt || 1 / 30); sw.tabs(app); });
})(typeof self !== 'undefined' ? self : this);
