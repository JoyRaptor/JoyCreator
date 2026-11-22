/* AvatarLab: GESTURES ON THE JOYSTICK (the owner, 11-21 late). The joystick is coarse for fine placing, but its pad can do more:
 * a quick flick or a tap on it is a GESTURE, and a press held and steered is the joystick as before.
 *
 *   Tap, Two taps, Swipe up, Swipe down, Swipe left, Swipe right    six slots, each filled with one act
 *
 * The acts on offer are only what this character can really do (a dead choice is worse than a missing one):
 *   moving    Jump, Dash left, Dash right, Back to the middle (a character that walks or floats), Turn round
 *   its own   every one of its buttons: its stances (sitting, on four legs...), its faces, its layers (a flashlight)...
 *   alive     Blink, Next mood, Show-off loop on or off
 * Each character keeps its own (ch.settings.stick), so Topps can jump on a flick up and a ghost can fade. Changing one is one Undo
 * step. The small button in the pad's corner opens them all; a gesture says what it did on the pad for a moment.
 * Tumble, a clip and the like need moves the room does not have yet: they will be offered when they exist.
 *
 * Each act is what a finger would do: a jump pulls the body up past where its feet let go and lets it fall, a dash sends it a long
 * way at its own legs' speed, a button is the button. So recording and passes take them like any other touch.
 *
 * Phone: the same slots on the pad's gesture detector (tap, double tap, fling); the sheet is a ModalBottomSheet of LazyRows.
 */
(function (root) {
  'use strict';
  var AL = root.AL, h = AL.h, moves = AL.stickMoves = {}, NS = 'http://www.w3.org/2000/svg';
  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  moves.SLOTS = [['tap', 'Tap'], ['dtap', 'Two taps'], ['up', 'Swipe up'], ['down', 'Swipe down'], ['left', 'Swipe left'], ['right', 'Swipe right']];
  moves.DOUBLE = 280;                         // ms a first tap waits to see if a second follows (only when Two taps has an act)

  document.head.appendChild(h('style', { text: [
    '.stickpad .padset{position:absolute;right:4px;top:4px;width:28px;height:28px;border-radius:50%;display:grid;place-items:center;color:var(--dim);background:rgba(255,255,255,.04);z-index:1}',
    '.stickpad .padset:hover{color:var(--ink);background:var(--pressed)} .stickpad .padset svg{width:17px;height:17px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}',
    '.gsh{display:flex;align-items:center;gap:8px;margin:12px 0 6px;font:600 12.5px/1 var(--f-body);color:var(--ink)} .gsh:first-child{margin-top:2px}',
    '.gsh svg{width:22px;height:22px;flex:none;fill:none;stroke:var(--live);stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}',
    '.gsh small{margin-left:auto;font:500 10px/1 var(--f-mono);letter-spacing:.06em;text-transform:uppercase;color:var(--faint)}',
    '.gsrow{display:flex;gap:6px;overflow-x:auto;scrollbar-width:none;padding:1px 0 3px} .gsrow::-webkit-scrollbar{display:none}',
    '.gsrow .chip img{width:22px;height:22px;object-fit:contain;margin-left:-6px}'
  ].join('\n') }));
  function svg(d, cls) { var s = document.createElementNS(NS, 'svg'); s.setAttribute('viewBox', '0 0 24 24'); if (cls) s.setAttribute('class', cls); s.setAttribute('aria-hidden', 'true'); var p = document.createElementNS(NS, 'path'); p.setAttribute('d', d); s.appendChild(p); return s; }
  /* the gesture itself, drawn: a fingertip, or a fingertip and the way it flicks */
  var GLYPH = { tap: 'M12 10.2a1.8 1.8 0 100 3.6 1.8 1.8 0 000-3.6zM7.8 7.8a6 6 0 018.4 0', dtap: 'M8.5 10.2a1.8 1.8 0 100 3.6 1.8 1.8 0 000-3.6zM15.5 10.2a1.8 1.8 0 100 3.6 1.8 1.8 0 000-3.6zM6 7.5a5 5 0 015 0M13 7.5a5 5 0 015 0',
    up: 'M12 19a1.8 1.8 0 100 3.6 1.8 1.8 0 000-3.6zM12 16V3M8 7l4-4 4 4', down: 'M12 1.4a1.8 1.8 0 100 3.6 1.8 1.8 0 000-3.6zM12 8v13M8 17l4 4 4-4',
    left: 'M19 10.2a1.8 1.8 0 100 3.6 1.8 1.8 0 000-3.6zM16 12H3M7 8l-4 4 4 4', right: 'M5 10.2a1.8 1.8 0 100 3.6 1.8 1.8 0 000-3.6zM8 12h13M17 8l4 4-4 4' };
  moves.glyph = function (slot) { return svg(GLYPH[slot] || GLYPH.tap); };
  /* the moving acts' own pictures, for their chips (AL.icon reads AL.PICS) */
  if (AL.PICS) { AL.PICS['mv-jump'] = { d: 'M12 21v-9M8 8l4-4 4 4M6 21h12' }; AL.PICS['mv-left'] = { d: 'M20 12H5M9 7l-5 5 5 5M20 8v8' }; AL.PICS['mv-right'] = { d: 'M4 12h15M15 7l5 5-5 5M4 8v8' }; }

  /* ── the acts this character can do ── */
  function travels(app) { return !!(app.inp && app.inp.walker && app.inp.walker.travels()); }
  function poseFor(app, trigId) { return (app.ch.poses || []).filter(function (p) { return p.trig === trigId; })[0] || null; }
  /** Every act on offer for the character in hand: { key, name, icon | pic, title }. */
  moves.acts = function (app) {
    var l = [{ key: 'none', name: 'Nothing', icon: 'x', title: 'This gesture does nothing' }];
    if (travels(app)) {
      l.push({ key: 'jump', name: 'Jump', icon: 'mv-jump', title: 'Up past where the feet let go, and down again' });
      l.push({ key: 'dash-l', name: 'Dash left', icon: 'mv-left', title: 'Off to the left as fast as its legs go' });
      l.push({ key: 'dash-r', name: 'Dash right', icon: 'mv-right', title: 'Off to the right as fast as its legs go' });
      l.push({ key: 'home', name: 'Back to the middle', icon: 'home', title: 'Walk back to where it started' });
    }
    l.push({ key: 'flip', name: 'Turn round', icon: 'flip', title: 'Face the other way' });
    (app.ch.triggers || []).forEach(function (t) { var p = poseFor(app, t.id), pic = p && AL.stages && AL.stages.poseThumb ? AL.stages.poseThumb(app, p.id) : null; l.push({ key: 'trig:' + t.id, name: t.name, icon: 'bolt', pic: pic, title: (p ? 'The stance “' : 'Its button “') + t.name + '”, on and off' }); });
    l.push({ key: 'blink', name: 'Blink', icon: 'eye', title: 'A blink' });
    if (AL.hasLids && AL.hasLids(app.ch)) l.push({ key: 'mood', name: 'Next mood', icon: 'smile', title: 'Its eyelids and brows step to the next mood' });
    l.push({ key: 'auto', name: 'Show-off loop', icon: 'loop', title: 'It performs by itself, on and off' });
    return l;
  };
  /** The gestures as they are now for this character (its own, or the ones it starts with). */
  moves.map = function (app) {
    var own = (app.ch.settings && app.ch.settings.stick) || {}, keys = {}, out = {}, stance = (app.ch.poses || []).filter(function (p) { return p.trig; })[0];
    moves.acts(app).forEach(function (a) { keys[a.key] = 1; });
    var def = { tap: stance ? 'trig:' + stance.trig : 'blink', dtap: 'flip', up: travels(app) ? 'jump' : 'blink', down: 'none', left: travels(app) ? 'dash-l' : 'none', right: travels(app) ? 'dash-r' : 'none' };
    moves.SLOTS.forEach(function (s) { var k = own[s[0]]; out[s[0]] = k && keys[k] ? k : keys[def[s[0]]] ? def[s[0]] : 'none'; });      // (an act this character cannot do falls back, never a dead one)
    return out;
  };
  /** Fill a slot: one Undo step. */
  moves.set = function (app, slot, key) {
    var a = moves.acts(app).filter(function (q) { return q.key === key; })[0], sn = moves.SLOTS.filter(function (s) { return s[0] === slot; })[0];
    app.edit(function () { var st = app.ch.settings; st.stick = st.stick || {}; st.stick[slot] = key; }, true, 'Gesture: ' + (sn ? sn[1] : slot) + ', ' + (a ? a.name : key));
  };

  /* ── doing it: what a finger would do ── */
  function pulse(inp, ms) { inp.held.body = true; clearTimeout(inp._mv); inp._mv = setTimeout(function () { inp.held.body = false; }, ms); }
  function size(app) { var b = AL.boundsOf(app.ch.root) || { x0: 0, y0: 0, x1: app.ch.w, y1: app.ch.h }; return { w: Math.max(20, b.x1 - b.x0), h: Math.max(20, b.y1 - b.y0) }; }
  moves.run = function (app, key) {
    var inp = app.inp, w = inp && inp.walker, z = size(app), a = moves.acts(app).filter(function (q) { return q.key === key; })[0];
    if (!inp || !a || key === 'none') return null;
    if (key === 'jump') {
      var band = w.band, up = band ? band.up * 1.35 + z.h * 0.05 : z.h * 0.35;
      if (w.grounded()) { w.grab([0, 0]); w.drag([0, -up]); inp.held.body = true; setTimeout(function () { if (w.g0) w.release(); inp.held.body = false; }, 230); }      // pulled up past where the feet let go, then let go: it falls by itself
      else { var y0 = w.ty; w.ty = y0 - z.h * 0.35; pulse(inp, 520); setTimeout(function () { w.ty = y0; }, 300); }      // (floating: up and back)
    } else if (key === 'dash-l' || key === 'dash-r') { var lim = z.w * 0.7; w.tx = clamp(w.tx + (key === 'dash-r' ? 1 : -1) * z.w * 0.6, -lim, lim); pulse(inp, 900); }
    else if (key === 'home') { w.home(); pulse(inp, 900); }
    else if (key === 'flip') { inp.pose.flip = !inp.pose.flip; pulse(inp, 400); }
    else if (key === 'blink') inp.blinkNow();
    else if (key === 'mood') { var ms = Object.keys(AL.MOODS), i = ms.indexOf(inp.pose.mood); inp.setMood(ms[(i + 1) % ms.length]); }
    else if (key === 'auto') { inp.auto = !inp.auto; if (!inp.auto) inp.resetPose(); }
    else if (key.slice(0, 5) === 'trig:') inp.toggleTrig(key.slice(5));
    var name = key === 'mood' ? inp.pose.mood : key === 'auto' ? 'Show-off ' + (inp.auto ? 'on' : 'off') : key.slice(0, 5) === 'trig:' ? a.name + (inp.trig[key.slice(5)] ? ' on' : ' off') : a.name;
    if (AL.stick) AL.stick.flash = { text: name, until: performance.now() + 900 };
    if (/^(mood|auto|trig:)/.test(key)) setTimeout(function () { app.refreshPanel(true); }, 0);      // (its button on the card shows it)
    return name;
  };
  /** A gesture came off the pad. A tap waits a moment for a second one, but only when Two taps has something to do. */
  var waiting = null;
  moves.gesture = function (app, kind) {
    var m = moves.map(app);
    if (kind !== 'tap') return moves.run(app, m[kind]);
    if (waiting) { clearTimeout(waiting); waiting = null; return moves.run(app, m.dtap); }
    if (m.dtap === 'none' || !moves.DOUBLE) return moves.run(app, m.tap);
    waiting = setTimeout(function () { waiting = null; moves.run(app, m.tap); }, moves.DOUBLE);
    return null;
  };

  /* ── changing them: the small button in the pad's corner ── */
  moves.button = function (app) {
    return h('button', { class: 'padset', title: 'Gestures: what a tap, two taps and a swipe on this pad do', 'aria-label': 'Gestures', onclick: function (e) { e.stopPropagation(); moves.sheet(app); } }, svg('M4 7h9M17 7h3M4 17h3M11 17h9M15 5v4M9 15v4'));      // (its own settings: two sliders)
  };
  moves.sheet = function (app) {
    var ui = app.ui, m = moves.map(app), acts = moves.acts(app), body = h('div');
    moves.SLOTS.forEach(function (s) {
      var cur = acts.filter(function (a) { return a.key === m[s[0]]; })[0];
      body.appendChild(h('div', { class: 'gsh' }, [moves.glyph(s[0]), h('span', { text: s[1] }), h('small', { text: cur ? cur.name : 'Nothing' })]));
      var row = h('div', { class: 'gsrow', role: 'group', 'aria-label': s[1] });
      acts.forEach(function (a) {
        var c = ui.chip(a.name, { on: a.key === m[s[0]], icon: a.pic ? null : a.icon, title: s[1] + ': ' + a.title, fn: function () { moves.set(app, s[0], a.key); moves.sheet(app); } });
        if (a.pic) c.insertBefore(h('img', { src: a.pic, alt: '' }), c.firstChild);
        row.appendChild(c);
      });
      body.appendChild(row);
    });
    body.appendChild(ui.note('These belong to <b>' + String(app.ch.name).replace(/[&<>]/g, '') + '</b>: each character keeps its own. A quick flick or tap on the pad is a gesture; a press you hold and steer is the joystick. Its own stances and buttons are here, so a sitting stance or a flashlight layer shows up once the character has one. Tumbles and clips need moves the room does not have yet.'));
    app.sheet('Joystick gestures', body, [{ label: 'Done', cls: 'quiet', fn: app.closeSheet }]);
    setTimeout(function () { Array.prototype.forEach.call(body.querySelectorAll('.gsrow'), function (r) { var on = r.querySelector('.chip.on'); if (on) r.scrollLeft = Math.max(0, on.offsetLeft - r.offsetLeft - 8); }); }, 0);      // (each row shows its chosen act)
  };
})(typeof self !== 'undefined' ? self : this);
