/* AvatarLab: THE SHARED FILE FORMAT. One character file and one scene file, read and written the same way by this
 * pilot and by the phone room. Spec: tasks/avatar/AVA_PHONE_SPEC.md, part B. Round-trip tests: tasks/avatar/format-tests/.
 *
 * Not loaded by index.html (nothing in the pilot changes by having this file). Two ways to run it:
 *   in the page:  <script src="al-format.js"></script>, then AL.format.check(doc), AL.format.upgrade(doc) ...
 *   on the PC:    node tasks/avatar/format-tests/run.mjs   (every test file, both ways round)
 *
 * WHAT IT DOES
 *   upgrade(doc)        a joycreator.character/1 file becomes /2: every set's pair-by-pair `bridges` becomes ONE numbered set
 *                       of landmarks per piece (`marks`), the owner's model of 11-23. Nothing else changes. Returns
 *                       { doc, report }: the report says, in plain words, what was joined, split or dropped.
 *   downgrade(doc)      /2 back to /1 pairs, so the pilot as it is today can open a /2 file until it reads marks itself.
 *   check(doc)          every rule of part B of the spec; returns a list of { code, where, say } (empty = good).
 *   canonical(doc)      the document as text with its keys in order, so two files can be compared exactly.
 *   blend(set, w)       where each landmark is for a turn that is w = { angleId: weight }: the shared rule both the pilot
 *                       and the phone solver use (each point blends between its own places; "not here" takes no part).
 *
 * THE LANDMARK MODEL (set.marks), per piece of a set ('' = the whole drawing, else the piece's lower-case name):
 *   set.marks = { '': { pts: [{ n: 1, name: 'tip of the nose' }, ...],
 *                       at:  { <angle id>: [ [x, y], [x, y, 'behind'], [x, y, 'off'], ... ] } },     index i = point pts[i]
 *                 'frill': { ... } }
 *   [x, y]            the point is here in this angle's drawing (character space, pixels, y down)
 *   [x, y, 'behind']  folded round behind: a ghost. It still has a place and still steers the bend toward this angle.
 *   [x, y, 'off']     "not here": greyed, sits anywhere, takes no part in this angle at all.
 *   An angle missing from `at` has no landmarks for that piece (exactly as an unmatched angle today).
 *   A mirrored angle (type 'mirror') has no entry of its own unless it was placed by hand: it reads its source's, mirrored
 *   about set.axisX (or the character's middle).
 *   marks[piece].meets = [{ a, b, at }]   the old "They meet" slider (0..1: where drawings a and b meet on the way between
 *                                them), per piece and per pair of angles as in the pilot. Absent = 0.5 (half way).
 *
 * Phone: com.fadcam.ui.faditor.avatar.file.CharacterFile / SceneFile read and write the same JSON (Gson); this file's
 * check() rules are its unit test (tools/jvm-harness, the same test files).
 */
(function (root) {
  'use strict';
  var AL = root.AL = root.AL || {};
  var F = AL.format = {};
  F.CHARACTER = 'joycreator.character/2';
  F.CHARACTER_OLD = 'joycreator.character/1';
  F.SCENE = 'joycreator.scene/1';
  F.JOIN_PX = 4;          // two landmark dots in the same drawing closer than this are the same landmark
  F.SPLIT_PX = 6;         // one landmark given two places further apart than this in one drawing is split in two

  var TYPES = ['group', 'set', 'art', 'mirror'];
  var PICKS = ['turn', 'reach', 'viseme', 'default', 'cycle', 'dir', 'trig'];
  var STATES = { behind: 1, off: 1 };
  var KINDS = { back: 1, obj: 1, front: 1, cast: 1 };
  var SHAPES = ['16:9', '4:3', '1:1', '4:5', '9:16'];

  function copy(o) { return JSON.parse(JSON.stringify(o)); }
  function num(v) { return typeof v === 'number' && isFinite(v); }
  function xy(v) { return Array.isArray(v) && v.length >= 2 && num(v[0]) && num(v[1]); }
  function d2(a, b) { return Math.hypot(a[0] - b[0], a[1] - b[1]); }
  function walk(n, fn, par) { if (!n || typeof n !== 'object') return; fn(n, par || null); if (Array.isArray(n.kids)) n.kids.forEach(function (k) { walk(k, fn, n); }); }

  /* ── one set's pairs into one numbered set per piece ───────────────────────────────────────── */
  /** The pilot's pairs: bridges = [{ a, b, part?, pts: [{ a: [x,y], b: [x,y] }], meet }] (a, b: the two angles' ids; in each
      landmark `a` is its place in drawing a and `b` in drawing b, al-bridge.js commit()). */
  F.bridgesToMarks = function (set, report) {
    report = report || [];
    var bs = Array.isArray(set.bridges) ? set.bridges : [], byPart = {}, meets = {};
    bs.forEach(function (br) {
      if (!br || !br.a || !br.b) { report.push('A landmark pair with no angles was dropped.'); return; }
      var pk = br.part || '';
      (byPart[pk] = byPart[pk] || []).push(br);
      if (br.meet != null && Math.abs(br.meet - 0.5) > 1e-6) (meets[pk] = meets[pk] || []).push({ a: br.a, b: br.b, at: br.meet });
    });
    var marks = {};
    Object.keys(byPart).forEach(function (pk) {
      /* every dot is (angle, place). Dots joined by a pair are one landmark; dots in the same angle closer than JOIN_PX are
         one landmark too (the same spot matched to two neighbours). Union-find over the dots. */
      var dots = [], par = [];
      function dot(angle, p) { dots.push({ angle: angle, p: [p[0], p[1]] }); par.push(par.length); return dots.length - 1; }
      function find(i) { while (par[i] !== i) { par[i] = par[par[i]]; i = par[i]; } return i; }
      function join(i, j) { i = find(i); j = find(j); if (i !== j) { if (i < j) par[j] = i; else par[i] = j; } }
      byPart[pk].forEach(function (br) {
        (br.pts || []).forEach(function (q) {
          var pa = q.a, pb = q.b;
          if (!xy(pa) || !xy(pb)) { report.push('A landmark with a missing place was dropped (' + (pk || 'whole drawing') + ').'); return; }
          var i1 = dot(br.a, pa), i2 = dot(br.b, pb); dots[i1].mate = i2; dots[i2].mate = i1; join(i1, i2);
        });
      });
      for (var i = 0; i < dots.length; i++) for (var j = i + 1; j < dots.length; j++) if (dots[i].angle === dots[j].angle && d2(dots[i].p, dots[j].p) < F.JOIN_PX) join(i, j);
      /* each class is one numbered landmark, numbered in the order it was first marked */
      var classes = [], at = {}, seen = {};
      dots.forEach(function (d, i) { var r = find(i); if (seen[r] == null) { seen[r] = classes.length; classes.push([]); } classes[seen[r]].push(d); });
      /* a class with two places far apart in one angle (a chain of pairs that disagrees) is split: the first place keeps the
         number, each disagreeing place starts a landmark of its own, with the place in the other drawing it was paired with */
      var out = [];
      classes.forEach(function (cl) {
        var main = {}, extra = [];
        cl.forEach(function (d) {
          var m = main[d.angle];
          if (!m) { main[d.angle] = { sum: [d.p[0], d.p[1]], n: 1, first: d.p }; return; }
          if (d2(m.first, d.p) <= F.SPLIT_PX) { m.sum[0] += d.p[0]; m.sum[1] += d.p[1]; m.n++; return; }
          extra.push(d);
        });
        var places = {}; Object.keys(main).forEach(function (a) { var m = main[a]; places[a] = [round(m.sum[0] / m.n), round(m.sum[1] / m.n)]; });
        out.push(places);
        if (extra.length) {
          report.push('One landmark of ' + (pk || 'the whole drawing') + ' had two different places in one drawing; the second became a landmark of its own.');
          extra.forEach(function (d) { var pl = {}, m = dots[d.mate]; pl[d.angle] = [round(d.p[0]), round(d.p[1])]; if (m && m.angle !== d.angle) pl[m.angle] = [round(m.p[0]), round(m.p[1])]; out.push(pl); });      // (with the place it was paired with)
        }
      });
      /* every angle that has any landmark of this piece gets a full row: places it has, and for the rest "not here",
         parked at the average of where the point is in the drawings that have it (so it can be found and dragged) */
      var angles = {}; out.forEach(function (pl) { Object.keys(pl).forEach(function (a) { angles[a] = 1; }); });
      var pts = out.map(function (pl, i) { return { n: i + 1 }; });
      Object.keys(angles).forEach(function (a) {
        at[a] = out.map(function (pl) {
          if (pl[a]) return pl[a];
          var ks = Object.keys(pl), sx = 0, sy = 0; ks.forEach(function (k) { sx += pl[k][0]; sy += pl[k][1]; });
          return [round(sx / ks.length), round(sy / ks.length), 'off'];
        });
      });
      var joined = byPart[pk].reduce(function (s, br) { return s + (br.pts || []).length; }, 0);
      if (joined !== pts.length) report.push((pk || 'The whole drawing') + ': ' + joined + ' landmark pairs became ' + pts.length + ' numbered landmarks.');
      marks[pk] = { pts: pts, at: at }; if (meets[pk]) marks[pk].meets = meets[pk];
    });
    return marks;
  };
  function round(v) { return Math.round(v * 100) / 100; }

  /** The other way: every two angles that both place a landmark (neither "not here") become a pair, as the pilot reads today. */
  F.marksToBridges = function (set) {
    var out = [], marks = set.marks || {};
    Object.keys(marks).forEach(function (pk) {
      var mk = marks[pk], ids = Object.keys(mk.at || {}), meetOf = {};
      (mk.meets || []).forEach(function (m) { meetOf[m.a + '|' + m.b] = m.at; meetOf[m.b + '|' + m.a] = 1 - m.at; });
      for (var i = 0; i < ids.length; i++) for (var j = i + 1; j < ids.length; j++) {
        var A = ids[i], B = ids[j], pts = [];
        mk.pts.forEach(function (p, k) { var pa = mk.at[A][k], pb = mk.at[B][k]; if (!pa || !pb || pa[2] === 'off' || pb[2] === 'off') return; pts.push({ a: [pa[0], pa[1]], b: [pb[0], pb[1]] }); });
        if (!pts.length) continue;
        var br = { a: A, b: B, pts: pts, meet: meetOf[A + '|' + B] != null ? meetOf[A + '|' + B] : 0.5 }; if (pk) br.part = pk; out.push(br);
      }
    });
    return out;
  };

  /** Where each landmark of one piece is, for a turn whose angles weigh w = { angleId: weight } (weights need not sum to 1).
      Each point blends between ITS OWN places in the angles that have it; "not here" takes no part; "behind" counts in full.
      Returns [[x, y] | null per point]. null = no angle in the blend has that point. */
  F.blend = function (set, pk, w, axisX) {
    var mk = (set.marks || {})[pk || '']; if (!mk) return [];
    var kids = {}; (set.kids || []).forEach(function (k) { kids[k.id] = k; });
    return mk.pts.map(function (p, i) {
      var sx = 0, sy = 0, sw = 0;
      Object.keys(w).forEach(function (a) {
        var q = F.place(set, mk, a, i, axisX, kids); if (!q || q[2] === 'off' || !(w[a] > 0)) return;
        sx += q[0] * w[a]; sy += q[1] * w[a]; sw += w[a];
      });
      return sw > 0 ? [sx / sw, sy / sw] : null;
    });
  };
  /** One landmark's place in one angle, reading a mirrored angle from its source. */
  F.place = function (set, mk, angleId, i, axisX, kids) {
    var row = mk.at[angleId]; if (row) return row[i] || null;
    var k = kids && kids[angleId]; if (!k || k.type !== 'mirror' || !mk.at[k.of]) return null;
    var q = mk.at[k.of][i]; if (!q) return null; var ax = set.axisX != null ? set.axisX : axisX;
    return q.length > 2 ? [2 * ax - q[0], q[1], q[2]] : [2 * ax - q[0], q[1]];
  };

  /* ── the file, /1 to /2 and back ──────────────────────────────────────────────────────────── */
  /** inPlace: change the document itself (the pilot opening a file: no second copy of its pictures in memory). */
  F.upgrade = function (doc, inPlace) {
    var report = [];
    if (doc && doc.format === F.CHARACTER) return { doc: inPlace ? doc : copy(doc), report: report };
    var o = inPlace ? doc : copy(doc);
    if (o.format && o.format !== F.CHARACTER_OLD) { report.push('Not a character file this reads (' + o.format + ').'); return { doc: o, report: report }; }
    var nb = 0;
    walk(o.root, function (n) {
      if (n.type !== 'set' || !Array.isArray(n.bridges)) return;
      if (n.bridges.length) { n.marks = F.bridgesToMarks(n, report); nb++; }
      delete n.bridges;
    });
    if (nb) report.push(nb + (nb === 1 ? ' part' : ' parts') + ' had landmarks matched pair by pair; each piece now has one numbered set.');
    o.format = F.CHARACTER;
    return { doc: o, report: report };
  };
  F.downgrade = function (doc) {
    var o = copy(doc);
    walk(o.root, function (n) { if (n.type !== 'set' || !n.marks) return; var bs = F.marksToBridges(n); if (bs.length) n.bridges = bs; delete n.marks; });
    o.format = F.CHARACTER_OLD;
    return o;
  };
  /** A scene's characters ride inside it at the current version too. */
  F.upgradeScene = function (doc) {
    var o = copy(doc), report = [];
    Object.keys(o.characters || {}).forEach(function (id) { var r = F.upgrade(o.characters[id]); o.characters[id] = r.doc; r.report.forEach(function (s) { report.push(id + ': ' + s); }); });
    return { doc: o, report: report };
  };

  /* ── the rules ────────────────────────────────────────────────────────────────────────────── */
  F.check = function (doc) {
    var bad = [];
    function say(code, where, s) { bad.push({ code: code, where: where, say: s }); }
    if (!doc || typeof doc !== 'object') { say('not-json', '', 'That is not a file this reads.'); return bad; }
    if (doc.format === F.SCENE) return F.checkScene(doc);
    if (doc.format !== F.CHARACTER && doc.format !== F.CHARACTER_OLD) say('format', 'format', 'Unknown format: ' + doc.format);
    if (!(doc.w > 0) || !(doc.h > 0)) say('size', 'w,h', 'The character has no size.');
    if (!doc.root || typeof doc.root !== 'object') { say('root', 'root', 'The character has no layers.'); return bad; }
    var ids = {}, count = 0, depth = 0;
    (function w(n, d) {
      count++; if (d > 40 || count > 20000) { say('tangle', n.id, 'The layers are tangled.'); return; }
      if (typeof n.id !== 'string' || !n.id) say('id', '?', 'A layer has no id.');
      else if (ids[n.id]) say('dup-id', n.id, 'Two layers share the id ' + n.id + '.');
      else ids[n.id] = n;
      if (TYPES.indexOf(n.type) < 0) say('type', n.id, 'Layer ' + n.id + ' has an unknown kind: ' + n.type);
      if (n.kids != null && !Array.isArray(n.kids)) say('kids', n.id, 'Layer ' + n.id + ' is damaged.');
      if (Array.isArray(n.kids)) n.kids.forEach(function (k) { if (k && typeof k === 'object') w(k, d + 1); else say('kids', n.id, 'Layer ' + n.id + ' is damaged.'); });
      depth = Math.max(depth, d);
    })(doc.root, 0);
    var pics = doc.pictures || null, trig = {};
    (doc.triggers || []).forEach(function (t) { if (!t || !t.id) say('trigger', '?', 'A button has no id.'); else trig[t.id] = t; });
    function ref(id, where, what) { if (id != null && !ids[id]) say('ref', where, what + ' points at ' + id + ', which is not in the file.'); }
    Object.keys(ids).forEach(function (id) {
      var n = ids[id];
      if (n.type === 'art') {
        if (!num(n.x) || !num(n.y)) say('art-xy', id, 'Drawing ' + id + ' has no place.');
        if (typeof n.img !== 'string' || !n.img) say('art-img', id, 'Drawing ' + id + ' has no picture.');
        else if (pics && !pics[n.img]) say('pic-missing', id, 'Drawing ' + id + '’s picture ' + n.img + ' is not in the file.');
      }
      if (n.type === 'mirror') ref(n.of, id, 'A mirrored angle');
      if (n.type === 'set') {
        var pk = n.pick || {};
        if (pk.by && PICKS.indexOf(pk.by) < 0) say('pick', id, 'Part ' + id + ' swaps by an unknown rule: ' + pk.by);
        ref(pk.rest, id, 'The resting drawing of ' + id);
        if (pk.by === 'turn') (n.kids || []).forEach(function (k) { if (k.type !== 'group' && k.type !== 'art' && k.type !== 'mirror' && k.type !== 'set') return; if (!xy(k.at)) say('angle-at', k.id, 'Head angle ' + k.id + ' has no dot on the pad.'); });
        if (n.bridges && doc.format === F.CHARACTER) say('pairs-in-v2', id, 'Part ' + id + ' still has pair-by-pair landmarks in a /2 file.');
        if (n.marks) checkMarks(n, say);
      }
      (n.clips || []).forEach(function (c) { ref(c && c.by, id, 'A mask on ' + id); });
      if (n.ride) ref(n.ride.of, id, 'What carries ' + id);
      if (n.trig && !trig[n.trig]) say('trig-ref', id, 'Drawing ' + id + '’s button ' + n.trig + ' is not in the file.');
    });
    (doc.poses || []).forEach(function (p, i) {
      if (!p || !p.id || !p.name) { say('pose', 'poses[' + i + ']', 'A stance has no name.'); return; }
      if (p.trig && !trig[p.trig]) say('trig-ref', p.id, 'Stance ' + p.name + '’s button is not in the file.');
      Object.keys(p.d || {}).forEach(function (k) { ref(k, p.id, 'Stance ' + p.name); });
    });
    (doc.palettes || []).forEach(function (p, i) {
      if (!p || !p.id || !Array.isArray(p.stops)) { say('palette', 'palettes[' + i + ']', 'A colour set is damaged.'); return; }
      if (p.trig && !trig[p.trig]) say('trig-ref', p.id, 'Colour set ' + p.name + '’s button is not in the file.');
      p.stops.forEach(function (s) { if (!num(s.from) || !num(s.to)) say('palette', p.id, 'A colour in ' + p.name + ' has no hue.'); });
    });
    if (doc.take) checkTake(doc.take, 'take', say);
    if (pics) Object.keys(pics).forEach(function (k) { var v = pics[k]; if (typeof v !== 'string' || !(/^data:image\/(png|webp|jpeg);base64,/.test(v) || /^[^\/\\:]+\.pictures\/[^\/\\:]+\.(png|webp|jpg)$/.test(v))) say('pic-form', k, 'Picture ' + k + ' is neither inside the file nor beside it.'); });
    return bad;
  };
  function checkMarks(set, say) {
    var kidIds = {}; (set.kids || []).forEach(function (k) { kidIds[k.id] = k; });
    Object.keys(set.marks).forEach(function (pk) {
      var mk = set.marks[pk], where = set.id + (pk ? ' › ' + pk : '');
      if (!mk || !Array.isArray(mk.pts) || !mk.at || typeof mk.at !== 'object') { say('marks', where, 'The landmarks of ' + where + ' are damaged.'); return; }
      mk.pts.forEach(function (p, i) { if (!p || p.n !== i + 1) say('mark-n', where, 'Landmark ' + (i + 1) + ' of ' + where + ' is numbered ' + (p && p.n) + '.'); });
      Object.keys(mk.at).forEach(function (a) {
        if (!kidIds[a]) say('mark-angle', where, 'Landmarks of ' + where + ' are placed on ' + a + ', which is not one of its drawings.');
        var row = mk.at[a];
        if (!Array.isArray(row) || row.length !== mk.pts.length) { say('mark-row', where, a + ' does not place every landmark of ' + where + ' (' + (row && row.length) + ' of ' + mk.pts.length + ').'); return; }
        row.forEach(function (q, i) { if (!xy(q) || q.length > 3 || (q.length === 3 && !STATES[q[2]])) say('mark-place', where, 'Landmark ' + (i + 1) + ' in ' + a + ' has a bad place.'); });
        var live = row.filter(function (q) { return q && q[2] !== 'off'; }).length;
        if (live === 0) say('mark-empty', where, 'In ' + a + ' every landmark of ' + where + ' is "not here".');
      });
      (mk.meets || []).forEach(function (m) { if (!kidIds[m.a] || !kidIds[m.b] || !num(m.at) || m.at < 0 || m.at > 1) say('meet', where, 'A "they meet" setting on ' + where + ' is damaged.'); });
    });
  }
  function checkTake(t, where, say) {
    if (!t || typeof t !== 'object' || !(t.fps > 0) || !(t.n >= 0) || (t.n | 0) !== t.n || !t.ch || typeof t.ch !== 'object') { say('take', where, 'The take is damaged.'); return; }
    Object.keys(t.ch).forEach(function (k) { var a = t.ch[k]; if (!Array.isArray(a) || a.length > t.n + 1) say('take-lane', where + '.' + k, 'The take’s ' + k + ' is longer than the take.'); });
    if (t.voice) {
      var v = t.voice; if (!(v.rate > 0) || !(v.n >= 0) || typeof v.wav !== 'string' || !(/^data:audio\/wav;base64,/.test(v.wav) || /\.wav$/.test(v.wav))) say('voice', where, 'The take’s voice is damaged.');
      else if (Math.abs(v.n / v.rate - t.n / t.fps) > 1 / t.fps + 0.001) say('voice-len', where, 'The voice is not as long as the take (' + (v.n / v.rate).toFixed(3) + ' s against ' + (t.n / t.fps).toFixed(3) + ' s).');
    }
  }
  F.checkScene = function (doc) {
    var bad = [];
    function say(code, where, s) { bad.push({ code: code, where: where, say: s }); }
    if (doc.format !== F.SCENE) say('format', 'format', 'Unknown format: ' + doc.format);
    if (typeof doc.uid !== 'string' || !doc.uid) say('uid', 'uid', 'The scene has no id.');
    if (SHAPES.indexOf(doc.shape) < 0) say('shape', 'shape', 'The frame has an unknown shape: ' + doc.shape);
    if (!Array.isArray(doc.items)) { say('items', 'items', 'The scene has no list of things.'); return bad; }
    var ids = {}, shots = {};
    (doc.shots || []).forEach(function (s) { if (!s || !s.id || !num(s.x) || !num(s.y) || !(s.z > 0)) say('shot', s && s.id, 'A kept shot is damaged.'); else shots[s.id] = 1; });
    doc.items.forEach(function (it, i) {
      var w = 'items[' + i + ']';
      if (!it || !it.id) { say('item-id', w, 'A thing in the scene has no id.'); return; }
      if (ids[it.id]) say('dup-id', it.id, 'Two things share the id ' + it.id + '.'); ids[it.id] = it;
      if (!KINDS[it.kind]) say('kind', it.id, it.id + ' is an unknown kind of thing: ' + it.kind);
      if (!num(it.x) || !num(it.y)) say('item-xy', it.id, it.id + ' has no place.');
      if (it.kind === 'cast') { if (!it.uid && !it.stock && !(doc.characters && doc.characters[it.id])) say('cast-src', it.id, 'Character ' + (it.name || it.id) + ' says neither where it lives nor carries itself.'); }
      else if (!it.made && !it.img && !(Array.isArray(it.frames) && it.frames.length)) say('item-pic', it.id, (it.name || it.id) + ' has no picture.');
      if (doc.pictures) [it.img].concat(it.frames || []).forEach(function (p) { if (p && !it.made && !doc.pictures[p]) say('pic-missing', it.id, 'The picture ' + p + ' of ' + (it.name || it.id) + ' is not in the file.'); });
    });
    (doc.cam || []).forEach(function (c, i) {
      if (!c || !num(c.t) || c.t < 0 || typeof c.shot !== 'string') { say('cam', 'cam[' + i + ']', 'A cut on the camera lane is damaged.'); return; }
      if (!/^(wide|two|free)$/.test(c.shot) && !/^close:/.test(c.shot) && !shots[c.shot]) say('cam-shot', 'cam[' + i + ']', 'The camera lane cuts to a shot that is not in the scene: ' + c.shot);
      if (i && c.t < doc.cam[i - 1].t) say('cam-order', 'cam[' + i + ']', 'The camera lane is out of time order.');
    });
    Object.keys(doc.takes || {}).forEach(function (id) { if (!ids[id] || ids[id].kind !== 'cast') say('take-who', id, 'A take belongs to ' + id + ', which is not a character in the scene.'); else checkTake(doc.takes[id], 'takes.' + id, say); });
    Object.keys(doc.characters || {}).forEach(function (id) { if (!ids[id]) say('char-who', id, 'A carried character ' + id + ' is not in the scene.'); F.check(doc.characters[id]).forEach(function (b) { say(b.code, id + ' › ' + b.where, b.say); }); });
    return bad;
  };

  /* ── editing the landmarks (the pilot's landmarks screen, copying an angle and the solver use these; the phone's Marks class
        does the same). A place is [x, y], [x, y, 'behind'] or [x, y, 'off']. ── */
  var MK = AL.marks = {};
  /** "Not here": takes no part. A missing place counts as not here. */
  MK.off = function (q) { return !q || q[2] === 'off'; };
  MK.stateOf = function (q) { return !q ? 'off' : q[2] === 'off' || q[2] === 'behind' ? q[2] : 'here'; };
  MK.has = function (set) { var m = set && set.marks; if (!m) return false; for (var k in m) if (m[k] && m[k].pts && m[k].pts.length) return true; return false; };
  MK.piece = function (set, part, create) {
    var pk = part || '', all = set.marks || (create ? (set.marks = {}) : null); if (!all) return null;
    return all[pk] || (create ? (all[pk] = { pts: [], at: {} }) : null);
  };
  /** A drawing's row; made on first use with every point "not here", parked where `from` has it (else at 0, 0). */
  MK.row = function (mk, id, from) {
    if (mk.at[id]) return mk.at[id];
    var src = from && mk.at[from];
    return (mk.at[id] = mk.pts.map(function (p, i) { var q = src && src[i]; return [q ? q[0] : 0, q ? q[1] : 0, 'off']; }));
  };
  /** A new landmark: placed where `places` = { <drawing id>: [x, y] } says; in every other drawing "not here", parked at the first place. */
  MK.add = function (mk, places) {
    var ids = Object.keys(places), first = places[ids[0]];
    ids.forEach(function (id) { MK.row(mk, id, ids.filter(function (o) { return o !== id && mk.at[o]; })[0]); });
    mk.pts.push({ n: mk.pts.length + 1 });
    Object.keys(mk.at).forEach(function (id) { var p = places[id]; mk.at[id].push(p ? [p[0], p[1]] : [first[0], first[1], 'off']); });
    return mk.pts.length - 1;
  };
  /** Take landmark i out of every drawing; the rest are numbered again. An empty piece goes, and so do empty marks. */
  MK.remove = function (set, part, i) {
    var pk = part || '', mk = MK.piece(set, pk, false); if (!mk || !mk.pts[i]) return;
    mk.pts.splice(i, 1); Object.keys(mk.at).forEach(function (id) { mk.at[id].splice(i, 1); });
    mk.pts.forEach(function (p, k) { p.n = k + 1; });
    if (!mk.pts.length) MK.clear(set, pk);
  };
  MK.clear = function (set, part) { if (!set.marks) return; delete set.marks[part || '']; if (!Object.keys(set.marks).length) delete set.marks; };
  /** Here, behind or not here, for landmark i in one drawing. */
  MK.setState = function (mk, id, i, st) { var r = mk.at[id]; if (!r || !r[i]) return; var q = r[i]; r[i] = st === 'behind' || st === 'off' ? [q[0], q[1], st] : [q[0], q[1]]; };
  MK.move = function (mk, id, i, x, y) { var r = mk.at[id]; if (!r || !r[i]) return; var q = r[i]; r[i] = q.length > 2 ? [x, y, q[2]] : [x, y]; };
  /** Where drawings a and b meet on the way between them, as a share of the way from a to b (0.5 when never set). */
  MK.meet = function (mk, a, b) { var ms = mk.meets || []; for (var i = 0; i < ms.length; i++) { if (ms[i].a === a && ms[i].b === b) return ms[i].at; if (ms[i].a === b && ms[i].b === a) return 1 - ms[i].at; } return 0.5; };
  MK.setMeet = function (mk, a, b, v) {
    var ms = (mk.meets || []).filter(function (m) { return !((m.a === a && m.b === b) || (m.a === b && m.b === a)); });
    if (Math.abs(v - 0.5) > 1e-6) ms.push({ a: a, b: b, at: v });
    if (ms.length) mk.meets = ms; else delete mk.meets;
  };
  /** A copied drawing starts with every landmark exactly where its source has it (same states), in every piece. */
  MK.copyRows = function (set, from, to) { for (var k in (set.marks || {})) { var mk = set.marks[k]; if (mk.at[from]) mk.at[to] = mk.at[from].map(function (q) { return q.slice(); }); } };

  /** Text with keys in order: two documents that mean the same thing give the same text. */
  F.canonical = function (o) {
    return JSON.stringify(o, function (k, v) { if (v && typeof v === 'object' && !Array.isArray(v)) { var s = {}; Object.keys(v).sort().forEach(function (q) { s[q] = v[q]; }); return s; } return v; });
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = F;
})(typeof self !== 'undefined' ? self : typeof globalThis !== 'undefined' ? globalThis : this);
