/* AvatarLab: the character model.
 *
 * A character is a TREE with three kinds of node, the same three ideas a layered art file has:
 *   group : draw every child                       (a folder)
 *   set   : draw ONE child, picked by a rule       (a "swap set": head angles, arm drawings, mouth shapes)
 *   art   : one drawing, placed at x,y in character space (pixels, y down)
 * Behaviour hangs off any node as small optional records (limb, hinge, look, swing, lid, clips, breathe, links...).
 * Everything is plain JSON so it can be read by hand, by Joybot, and by the phone.
 *
 * Phone twin: AvatarRig (parts + pose domains) grows into this tree; nothing here is web-only.
 */
(function (root) {
  'use strict';
  var AL = root.AL = root.AL || {};
  var seq = 0;
  AL.id = function (p) { seq++; return (p || 'n') + seq.toString(36) + ((Math.random() * 46655) | 0).toString(36); };

  /* Roles: what a node IS. The body map, the automatic rig and the part cards all key off these. */
  AL.ROLES = {
    head: { label: 'Head', zone: 'head' }, face: { label: 'Face', zone: 'head' },
    eyeL: { label: 'Left eye', zone: 'head' }, eyeR: { label: 'Right eye', zone: 'head' },
    pupil: { label: 'Pupil', zone: 'head' }, eyeball: { label: 'Eye white', zone: 'head' }, blink: { label: 'Blink', zone: 'head' },
    lidU: { label: 'Upper lid', zone: 'head' }, lidL: { label: 'Lower lid', zone: 'head' },
    browL: { label: 'Left brow', zone: 'head' }, browR: { label: 'Right brow', zone: 'head' },
    nose: { label: 'Nose', zone: 'head' }, mouth: { label: 'Mouth', zone: 'head' }, jaw: { label: 'Jaw', zone: 'head' },
    hair: { label: 'Hair, ears, frill', zone: 'head' },
    body: { label: 'Body', zone: 'body' }, torso: { label: 'Torso', zone: 'body' },
    armL: { label: 'Left arm', zone: 'armL' }, armR: { label: 'Right arm', zone: 'armR' },
    legL: { label: 'Left leg', zone: 'legL' }, legR: { label: 'Right leg', zone: 'legR' },
    tail: { label: 'Tail', zone: 'tail' }, prop: { label: 'Something else', zone: 'extra' }
  };
  AL.ZONES = [
    { id: 'head', label: 'Head' }, { id: 'body', label: 'Body' }, { id: 'armR', label: 'Right arm' }, { id: 'armL', label: 'Left arm' },
    { id: 'legR', label: 'Right leg' }, { id: 'legL', label: 'Left leg' }, { id: 'tail', label: 'Tail' }, { id: 'extra', label: 'Extras' }
  ];
  /* Mouth sounds the lip-sync can ask for. A mouth set may have any subset. */
  AL.SHAPES = ['Neutral', 'Aa', 'Ee', 'Oh', 'W-Oo', 'M', 'F', 'L', 'S', 'D', 'R', 'Uh', 'Smile', 'Surprised'];

  AL.newCharacter = function (name, w, h) {
    var ch = {
      format: 'joycreator.avatar/1', name: name || 'New character', w: w || 512, h: h || 512,
      root: { id: AL.id('r'), name: name || 'Character', type: 'group', kids: [] },
      triggers: [],
      settings: { facing: 'front', autoFlip: false, flipAt: 0.4, blinkAuto: true, blinkEvery: 3.6, breathe: true, breathRate: 0.28, idle: true, eyesLead: true }
    };
    AL.reindex(ch);
    return ch;
  };

  /** id -> node and id -> parent. Hidden from JSON. Call after any structural change. */
  AL.reindex = function (ch) {
    var ix = { n: {}, p: {} };
    (function w(n, par) { ix.n[n.id] = n; ix.p[n.id] = par; if (n.kids) for (var i = 0; i < n.kids.length; i++) w(n.kids[i], n); })(ch.root, null);
    Object.defineProperty(ch, '_ix', { value: ix, enumerable: false, configurable: true, writable: true });
    return ix;
  };
  AL.ix = function (ch) { return ch._ix || AL.reindex(ch); };
  AL.node = function (ch, id) { return AL.ix(ch).n[id] || null; };
  AL.parentOf = function (ch, id) { return AL.ix(ch).p[id] || null; };
  AL.walk = function (n, fn, par, depth) {
    if (fn(n, par || null, depth || 0) === false) return;
    if (n.kids) for (var i = 0; i < n.kids.length; i++) AL.walk(n.kids[i], fn, n, (depth || 0) + 1);
  };
  AL.find = function (n, test) { var hit = null; AL.walk(n, function (k) { if (hit) return false; if (test(k)) { hit = k; return false; } }); return hit; };
  AL.findAll = function (n, test) { var out = []; AL.walk(n, function (k) { if (test(k)) out.push(k); }); return out; };
  AL.byRole = function (n, role) { return AL.findAll(n, function (k) { return k.role === role; }); };
  AL.firstRole = function (n, role) { return AL.find(n, function (k) { return k.role === role; }); };
  /** The zone (body-map area) a node belongs to: its own role's zone, else the nearest ancestor's. */
  AL.zoneOf = function (ch, id) {
    var ix = AL.ix(ch), n = ix.n[id];
    while (n) { if (n.role && AL.ROLES[n.role]) return AL.ROLES[n.role].zone; n = ix.p[n.id]; }
    return 'extra';
  };
  AL.ancestorWith = function (ch, id, test) { var ix = AL.ix(ch), n = ix.n[id]; while (n) { if (test(n)) return n; n = ix.p[n.id]; } return null; };
  AL.remove = function (ch, id) { var p = AL.parentOf(ch, id); if (!p) return; p.kids = p.kids.filter(function (k) { return k.id !== id; }); AL.reindex(ch); };

  /** Rest bounds of everything under a node, in character space. */
  AL.boundsOf = function (n) {
    var b = null;
    AL.walk(n, function (k) {
      if (k.type !== 'art') return; var im = AL.img.get(k.img); if (!im) return;
      var x0 = k.x, y0 = k.y, x1 = k.x + im.w, y1 = k.y + im.h;
      b = b ? { x0: Math.min(b.x0, x0), y0: Math.min(b.y0, y0), x1: Math.max(b.x1, x1), y1: Math.max(b.y1, y1) } : { x0: x0, y0: y0, x1: x1, y1: y1 };
    });
    return b;
  };

  /* ── pictures ─────────────────────────────────────────────────────────────── */
  AL.img = {
    map: {},
    put: function (id, canvas, name) { this.map[id] = { id: id, canvas: canvas, w: canvas.width, h: canvas.height, name: name || id, ver: (this.map[id] ? this.map[id].ver + 1 : 1), _a: null }; return id; },
    get: function (id) { return this.map[id] || null; },
    /** Alpha plane (0..255), cached. Hit tests and the pin guesses read this. */
    alpha: function (id) {
      var im = this.map[id]; if (!im) return null; if (im._a) return im._a;
      var d = im.canvas.getContext('2d').getImageData(0, 0, im.w, im.h).data, a = new Uint8Array(im.w * im.h);
      for (var i = 0, n = a.length; i < n; i++) a[i] = d[i * 4 + 3];
      return (im._a = a);
    },
    /** One pixel, [r,g,b,a]. */
    rgba: function (id, x, y) { var im = this.map[id]; if (!im || x < 0 || y < 0 || x >= im.w || y >= im.h) return [0, 0, 0, 0]; return im.canvas.getContext('2d').getImageData(x | 0, y | 0, 1, 1).data; },
    fromRGBA: function (id, w, h, data, name) {
      var c = document.createElement('canvas'); c.width = w; c.height = h;
      c.getContext('2d').putImageData(new ImageData(data, w, h), 0, 0);
      return this.put(id, c, name);
    },
    clear: function () { this.map = {}; }
  };

  /* ── saving ──────────────────────────────────────────────────────────────── */
  AL.toJson = function (ch, withPictures) {
    var o = JSON.parse(JSON.stringify(ch));
    if (withPictures) {
      o.pictures = {};
      AL.walk(ch.root, function (n) { if (n.type === 'art' && n.img && !o.pictures[n.img]) { var im = AL.img.get(n.img); if (im) o.pictures[n.img] = im.canvas.toDataURL('image/png'); } });
    }
    return o;
  };
  AL.fromJson = function (o) {
    var jobs = [];
    Object.keys(o.pictures || {}).forEach(function (id) {
      jobs.push(new Promise(function (res) {
        var im = new Image(); im.onload = function () { var c = document.createElement('canvas'); c.width = im.width; c.height = im.height; c.getContext('2d').drawImage(im, 0, 0); AL.img.put(id, c, id); res(); };
        im.onerror = function () { res(); }; im.src = o.pictures[id];
      }));
    });
    delete o.pictures;
    /* files saved before 11-18: the one-angle "dangle" became the jointed "swing" */
    AL.walk(o.root, function (n) { if (n.dangle) { n.swing = { kind: 'bendy', tip: n.dangle.tip, segs: 4, stiff: 0.35, damp: 0.3, follow: n.dangle.swing == null ? 1 : n.dangle.swing, wind: 1, wag: n.dangle.wag == null ? 0.04 : n.dangle.wag, on: n.dangle.on }; delete n.dangle; } });
    return Promise.all(jobs).then(function () { AL.reindex(o); return o; });
  };

  /* ── undo: one press, one step ───────────────────────────────────────────── */
  AL.History = function () { this.past = []; this.future = []; };
  AL.History.prototype.snap = function (ch) { return JSON.stringify(ch); };
  /** Call BEFORE a change, with the character as it is now. */
  AL.History.prototype.push = function (ch) { this.past.push(this.snap(ch)); if (this.past.length > 80) this.past.shift(); this.future.length = 0; };
  AL.History.prototype.undo = function (ch) { if (!this.past.length) return null; this.future.push(this.snap(ch)); var o = JSON.parse(this.past.pop()); AL.reindex(o); return o; };
  AL.History.prototype.redo = function (ch) { if (!this.future.length) return null; this.past.push(this.snap(ch)); var o = JSON.parse(this.future.pop()); AL.reindex(o); return o; };
})(typeof self !== 'undefined' ? self : this);
