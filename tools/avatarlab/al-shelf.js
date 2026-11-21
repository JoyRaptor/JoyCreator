/* AvatarLab: THE SHELF. The characters you have, as a row of cards at the top of the drawer.
 *
 * First a card to make a new one, then your own characters with the one used last first, then the ones that come with the
 * app. The row scrolls sideways, and its card widths are worked out so that one is always cut off at the edge: that is what
 * says "there are more". Every character you change is kept here by itself (nothing to press), so opening another one never
 * loses the one you were on.
 *
 * Kept in this browser beside the autosave: a short list ("shelf") and one document per character ("c:<id>").
 * Phone: the same row; characters are files in the Joy Creator folder, the list is the folder's listing by date.
 */
(function (root) {
  'use strict';
  var AL = root.AL, h = AL.h, shelf = AL.shelf = {}, KEEP = 12;
  function uid() { return 'c' + Date.now().toString(36) + Math.floor(Math.random() * 1e6).toString(36); }

  /** A small picture of the whole character as it stands, drawn the way the stage draws it (so eyelids, masks and colours are right). */
  shelf.thumb = function (app, px) {
    var ch = app.ch; if (!ch || !AL.find(ch.root, function (n) { return n.type === 'art'; })) return null;
    try {
      var T = shelf._t || (shelf._t = (function () { var c = document.createElement('canvas'); c.width = c.height = 264; return { c: c, G: new AL.GL(c) }; })());
      if (T.G.forget) T.G.forget();
      var sol = new AL.Solver(ch), S = AL.restSignals(), o = null; S.mouth = 0;
      for (var f = 0; f < 6; f++) { S.t = f / 30; o = sol.eval(S, { live: true, dt: 1 / 30 }); }
      var b = AL.boundsOf(ch.root) || { x0: 0, y0: 0, x1: ch.w, y1: ch.h }, s = Math.min(252 / Math.max(8, b.x1 - b.x0), 252 / Math.max(8, b.y1 - b.y0));
      T.G.draw(o.items, { s: s, ox: 132 - (b.x0 + b.x1) / 2 * s, oy: 132 - (b.y0 + b.y1) / 2 * s }, null, o.colour);
      var out = document.createElement('canvas'); out.width = out.height = px; out.getContext('2d').drawImage(T.c, 0, 0, px, px);
      return out.toDataURL('image/webp', 0.85);
    } catch (e) { return null; }
  };

  /** A small picture of the character posed some way (in a stance, say), drawn by the same solver as the stage. */
  shelf.snap = function (app, eo, px) {
    var ch = app.ch;
    try {
      var T = shelf._t || (shelf._t = (function () { var c = document.createElement('canvas'); c.width = c.height = 264; return { c: c, G: new AL.GL(c) }; })());
      if (T.G.forget) T.G.forget();
      var sol = new AL.Solver(ch), S = AL.restSignals(), o = null, opt = { live: false, dt: 1 / 30 }, k; for (k in eo) opt[k] = eo[k]; S.mouth = 0;
      for (var f = 0; f < 4; f++) { S.t = f / 30; o = sol.eval(S, opt); }
      var b = null; o.items.forEach(function (it) { if (it.ghost) return; var v = it.v; for (var i = 0; i < v.length; i += 2) { var x = v[i], y = v[i + 1]; if (!b) b = { x0: x, y0: y, x1: x, y1: y }; else { if (x < b.x0) b.x0 = x; if (x > b.x1) b.x1 = x; if (y < b.y0) b.y0 = y; if (y > b.y1) b.y1 = y; } } });
      if (!b) return null;
      var s = Math.min(252 / Math.max(8, b.x1 - b.x0), 252 / Math.max(8, b.y1 - b.y0));
      T.G.draw(o.items, { s: s, ox: 132 - (b.x0 + b.x1) / 2 * s, oy: 132 - (b.y0 + b.y1) / 2 * s }, null, o.colour);
      var out = document.createElement('canvas'); out.width = out.height = px; out.getContext('2d').drawImage(T.c, 0, 0, px, px);
      return out.toDataURL('image/webp', 0.85);
    } catch (e) { return null; }
  };
  /** A character worth keeping gets a name tag of its own, once, before it is first written. */
  shelf.tag = function (ch) { if (ch && !ch.uid && (ch.edited || !ch.demo)) ch.uid = uid(); };
  /* the ones that come with the app leave a picture of themselves the first time they are opened */
  shelf.stockPic = function (key) { try { return localStorage.getItem('al.stock.' + key) || null; } catch (e) { return null; } };
  shelf.stockKeep = function (app, key) { var ch = app.ch; if (!ch || ch.demo !== key || ch.edited) return; var p = shelf.thumb(app, 132); if (p) { try { localStorage.setItem('al.stock.' + key, p); } catch (e) { } } };
  shelf.list = function (app) { return app.idb(function (s) { return s.get('shelf'); }).then(function (l) { return Array.isArray(l) ? l : []; }, function () { return []; }); };
  /** Called after every autosave: a character that has been changed, or is your own, goes on the shelf (or moves to its front). */
  shelf.keep = function (app, doc) {
    var ch = app.ch; if (!ch || !(ch.edited || !ch.demo)) return;
    if (!AL.find(ch.root, function (n) { return n.type === 'art'; })) return;        // nothing drawn yet: nothing to show on a card
    if (!ch.uid) return;
    var id = ch.uid, pic = shelf.thumb(app, 132);
    return shelf.list(app).then(function (l) {
      var old = l.filter(function (e) { return e.uid === id; })[0], rest = l.filter(function (e) { return e.uid !== id; });
      rest.unshift({ uid: id, name: ch.name, at: Date.now(), pic: pic || (old && old.pic) || null });
      var drop = rest.slice(KEEP); rest = rest.slice(0, KEEP);
      return app.idb(function (s) { s.put(doc, 'c:' + id); drop.forEach(function (e) { s.delete('c:' + e.uid); }); return s.put(rest, 'shelf'); });
    }).catch(function () { });
  };
  /** Put the current one away (it is kept), then bring another out. */
  shelf.open = function (app, id) {
    app.dirty = !!app.ch && (app.dirty || app._state === 'unsaved');
    return Promise.resolve(app.autosave()).then(function () { return app.idb(function (s) { return s.get('c:' + id); }); }).then(function (o) {
      if (!o || AL.validate(o)) { app.toast('That character could not be opened.'); return; }
      delete o.take; AL.img.map = {};
      return AL.fromJson(o).then(function (ch) { app.setMode('build'); app.setCharacter(ch, { quiet: true }); app.dirty = true; app.autosave(); });
    });
  };
  shelf.remove = function (app, id) { return shelf.list(app).then(function (l) { return app.idb(function (s) { s.delete('c:' + id); return s.put(l.filter(function (e) { return e.uid !== id; }), 'shelf'); }); }); };

  /** The row itself. stock = [{ name, hint, fn, pic }] for the characters that come with the app. */
  shelf.row = function (app, stock, onNew) {
    var row = h('div', { class: 'shelf' }), wrap = h('div', { class: 'shelfw' }, row);
    function card(cls, pic, name, sub, title, fn) {
      return h('button', { class: 'sc ' + cls, title: title, 'aria-label': title, onclick: fn }, [pic, h('b', { text: name }), sub ? h('small', { text: sub }) : null]);
    }
    function fill(mine) {
      row.innerHTML = '';
      row.appendChild(card('new', AL.icon('plus'), 'Create new', null, 'Start a new character. The one you are on is kept.', onNew));
      var cur = app.ch && app.ch.uid;
      mine.forEach(function (e) {
        row.appendChild(card('own' + (e.uid === cur ? ' on' : ''), e.pic ? h('img', { src: e.pic, alt: '' }) : h('i'), e.name || 'Character', e.uid === cur ? 'open now' : null, e.uid === cur ? '“' + e.name + '” is the one you are on' : 'Open “' + e.name + '” as you left it', function () { if (e.uid === cur) { app.closeSheet(); return; } app.closeSheet(); shelf.open(app, e.uid); }));
      });
      stock.forEach(function (s) { row.appendChild(card('stock', s.pic ? h('img', { src: s.pic, alt: '' }) : h('i', null, AL.icon('smile')), s.name, s.hint || 'built in', s.title, function () { app.closeSheet(); app.dirty = !!app.ch && (app.dirty || app._state === 'unsaved'); Promise.resolve(app.autosave()).then(s.fn, s.fn); })); });
      size();
    }
    /* card width: as many as fit at about 96 wide, then squeezed or stretched so that the last one in view is cut in half */
    function size() {
      var W = row.clientWidth, n = row.children.length, gap = 8; if (!W) return;
      var fit = Math.max(2, Math.floor((W + gap) / (96 + gap))), w = n > fit ? (W + gap) / (fit + 0.45) - gap : 96;
      row.style.setProperty('--scw', Math.round(Math.max(78, Math.min(124, w))) + 'px');
      more();
    }
    function more() { wrap.classList.toggle('more', row.scrollWidth - row.clientWidth - row.scrollLeft > 6); }
    row.onscroll = more;
    fill([]); shelf.list(app).then(fill);
    setTimeout(size, 0);
    return wrap;
  };
})(typeof window !== 'undefined' ? window : globalThis);
