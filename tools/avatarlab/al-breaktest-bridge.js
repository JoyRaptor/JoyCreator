/**
 * MATCHED ANGLES MEET AT THE SWAP, WHATEVER ELSE IS MATCHED (owner's report, 11-23), on the owner's landmark model (one numbered set
 * of landmarks per piece; each point here, behind or not here in each drawing: set.marks, al-format.js).
 * A turn from Left Quarter up to Upward popped at half way once both had also been matched to Frontal, which sits beside that path.
 * This builds three plain squares on a pad (A, B, and C beside the path), with four numbered landmarks at their corners: B has them
 * 20 to the right, C 30 down. It checks that at the swap each corner of the drawing on show is where the two that swap meet; that a
 * point BEHIND in the other drawing still steers; that a point NOT HERE does not; that a mirrored angle reads its source's landmarks
 * mirrored; and that "They meet" moves the meeting point.
 *   await AL.breakTest.bridge.run()       the checks
 *   await AL.breakTest.bridge.proof()     each planted fault must be caught: the old pair model (the solver reading set.bridges),
 *                                         "not here" counted, "behind" dropped, mirrors ignored, "They meet" ignored
 * Load al-breaktest.js first. It works on a character of its own and puts back the one you were on.
 */
(function (root) {
  var AL = root.AL, T = AL.breakTest = AL.breakTest || {}, K = T.kit, Bt = T.bridge = {};
  function raf(n) { return new Promise(function (r) { var k = 0; (function f() { if (++k > n) r(); else requestAnimationFrame(f); })(); }); }
  function square(colour) { var c = document.createElement('canvas'); c.width = 100; c.height = 100; var g = c.getContext('2d'); g.fillStyle = colour; g.fillRect(0, 0, 100, 100); return c; }
  var X = 150, Y = 150, CORNERS = [[X, Y], [X + 100, Y], [X, Y + 100], [X + 100, Y + 100]];
  function make() {
    var ch = AL.newCharacter('Bridge test', 400, 400), kids = [];
    [['A', [0, 0], '#2dd4bf'], ['B', [1, 0], '#f472b6'], ['C', [0.5, 0.6], '#fbbf24']].forEach(function (k) {
      var id = AL.id('i'); AL.img.put(id, square(k[2]), k[0]);
      kids.push({ id: AL.id('n'), name: k[0], type: 'art', img: id, x: X, y: Y, at: k[1] });
    });
    kids.push({ id: AL.id('n'), name: 'B (mirrored)', type: 'mirror', of: kids[1].id, at: [-1, 0] });
    /* one numbered set: A at the corners, B 20 to the right, C 30 down */
    var at = {}; at[kids[0].id] = CORNERS.map(function (p) { return [p[0], p[1]]; }); at[kids[1].id] = CORNERS.map(function (p) { return [p[0] + 20, p[1]]; }); at[kids[2].id] = CORNERS.map(function (p) { return [p[0], p[1] + 30]; });
    var set = { id: AL.id('n'), name: 'Head', role: 'head', type: 'set', kids: kids, axisX: 200, pick: { by: 'turn', depth: 0, fade: 0, rest: kids[0].id },
      marks: { '': { pts: CORNERS.map(function (p, i) { return { n: i + 1 }; }), at: at } } };
    ch.root.kids.push(set); AL.reindex(ch);
    return { ch: ch, set: set, A: kids[0], B: kids[1], C: kids[2], D: kids[3], mk: set.marks[''] };
  }
  /* the corners of the drawing on show, as drawn this frame */
  function corners(app, art) {
    var it = app.out.items.filter(function (q) { return q.id === art.id && !q.ghost; })[0]; if (!it) return null;
    var nx = it.nx || Math.round(Math.sqrt(it.v.length / 2)) - 1, ny = it.v.length / 2 / (nx + 1) - 1, at = function (i, j) { var o = (j * (nx + 1) + i) * 2; return [it.v[o], it.v[o + 1]]; };
    return [at(0, 0), at(nx, 0), at(0, ny), at(nx, ny)];
  }
  function off(cn, d) { var w = 0; cn.forEach(function (p, k) { w = Math.max(w, Math.hypot(p[0] - (CORNERS[k][0] + d[0]), p[1] - (CORNERS[k][1] + d[1]))); }); return Math.round(w * 100) / 100; }
  async function at(app, x, y) { app.bt.x = x; app.bt.y = y; await raf(4); }

  Bt.run = async function () {
    var app = AL.app, R = K.mk(), ch0 = app.ch, bt0 = { x: app.bt.x, y: app.bt.y }, try0 = app.ballTry;
    try {
      var m = make(); app.setMode('build'); app.setCharacter(m.ch, { quiet: true }); app.ballTry = true;
      var cases = [['A', 0.49, m.A, [10, 0]], ['B', 0.51, m.B, [-10, 0]]];
      for (var i = 0; i < cases.length; i++) {
        var cs = cases[i]; await at(app, cs[1], 0);
        var shown = app.out.picks[m.set.id], cn = corners(app, cs[2]);
        R.F('bridge: ' + cs[0] + ' is the drawing on show at ' + Math.round(cs[1] * 100) + '% (the square beside the path never is)', shown === cs[2].id, { shown: (AL.node(app.ch, shown) || {}).name });
        if (!cn) continue;
        var w1 = off(cn, cs[3]); R.F('bridge: at the swap ' + cs[0] + '’s corners are where A and B meet, not pulled toward C beside the path', w1 < 0.6, { worstPx: w1 });
      }
      /* a turn straight to C still goes all the way to where A and C meet */
      await at(app, 0.5 * 0.49, 0.6 * 0.49);
      var cA = corners(app, m.A), wC = cA ? off(cA, [0, 15]) : 99;
      R.F('bridge: a turn straight toward C still meets C half way', app.out.picks[m.set.id] === m.A.id && wC < 0.6, { worstPx: wC });

      /* BEHIND still steers, NOT HERE does not: landmark 4 of B moved 40 down (a pull no other point makes) */
      var B4 = m.mk.at[m.B.id][3];
      m.mk.at[m.B.id][3] = [B4[0], B4[1] + 40, 'behind']; await at(app, 0.49, 0);
      var cb = corners(app, m.A), dyB = cb ? Math.round((cb[3][1] - CORNERS[3][1]) * 100) / 100 : 0;
      R.F('landmarks: a point BEHIND in B still pulls A’s corner toward where they meet (down)', dyB > 8, { downPx: dyB });
      m.mk.at[m.B.id][3] = [B4[0], B4[1] + 40]; await at(app, 0.49, 0);
      var ch2 = corners(app, m.A), dyH = ch2 ? Math.round((ch2[3][1] - CORNERS[3][1]) * 100) / 100 : 0;
      R.F('landmarks: behind pulls exactly as much as here (it counts in full)', Math.abs(dyH - dyB) < 0.3, { here: dyH, behind: dyB });
      m.mk.at[m.B.id][3] = [B4[0], B4[1] + 40, 'off']; await at(app, 0.49, 0);
      var co = corners(app, m.A), wo = co ? off(co, [10, 0]) : 99;
      R.F('landmarks: a point NOT HERE in B takes no part: A moves with the other three only', wo < 0.6, { worstPx: wo });
      m.mk.at[m.B.id][3] = [B4[0], B4[1]];

      /* a mirrored angle reads its source's landmarks, mirrored: toward "B (mirrored)" on the left, A moves 10 to the LEFT */
      await at(app, -0.49, 0);
      var cm = corners(app, m.A), wm = cm ? off(cm, [-10, 0]) : 99;
      R.F('landmarks: toward a mirrored angle, A meets it half way the mirrored way', app.out.picks[m.set.id] === m.A.id && wm < 0.6, { worstPx: wm });

      /* They meet: 0.3 of the way from A to B */
      AL.marks.setMeet(m.mk, m.A.id, m.B.id, 0.3); await at(app, 0.49, 0);
      var ct = corners(app, m.A), wt = ct ? off(ct, [6, 0]) : 99;
      R.F('landmarks: "They meet" at 30% puts A’s corners 6 px over at the swap, not 10', wt < 0.6, { worstPx: wt });
      AL.marks.setMeet(m.mk, m.A.id, m.B.id, 0.5);
    } catch (e) { R.F('THROWN', false, String(e && e.stack || e)); }
    app.bt.x = bt0.x; app.bt.y = bt0.y; app.ballTry = try0; app.setCharacter(ch0, { quiet: true }); await raf(2);
    return R.done ? R.done() : R;
  };

  /* THE OLD PAIR MODEL, kept here only as a planted fault: the solver reading set.bridges, as it did before 11-23 */
  function pairsBridgeFor(ch, set, A, B, part, full) {
    var bs = set.bridges || [], pk = part || '';
    for (var i = 0; i < bs.length; i++) { if ((bs[i].part || '') !== pk) continue; var rev = bs[i].a === B.id && bs[i].b === A.id; if (!rev && !(bs[i].a === A.id && bs[i].b === B.id)) continue; var meet = bs[i].meet == null ? 0.5 : bs[i].meet, m = full ? 1 : rev ? 1 - meet : meet; return bs[i].pts.map(function (q) { var pa = rev ? q.b : q.a, pb = rev ? q.a : q.b; return { from: [pa[0], pa[1]], to: [pa[0] + (pb[0] - pa[0]) * m, pa[1] + (pb[1] - pa[1]) * m] }; }); }
    return null;
  }
  Bt.FAULTS = {
    pairs: [/at the swap A/, function () { var o = AL.bridgeFor; AL.bridgeFor = pairsBridgeFor; return function () { AL.bridgeFor = o; }; }],
    offlive: [/NOT HERE/, function () { var o = AL.marks.off; AL.marks.off = function (q) { return !q; }; return function () { AL.marks.off = o; }; }],
    behinddrop: [/BEHIND in B still pulls/, function () { var o = AL.marks.off; AL.marks.off = function (q) { return !q || q[2] === 'off' || q[2] === 'behind'; }; return function () { AL.marks.off = o; }; }],
    nomirror: [/mirrored angle/, function () { var o = AL.bridgeFor; AL.bridgeFor = function (ch, set, A, B, p, f) { if (A.type === 'mirror' || B.type === 'mirror') return null; return o(ch, set, A, B, p, f); }; return function () { AL.bridgeFor = o; }; }],
    meet: [/They meet/, function () { var o = AL.marks.meet; AL.marks.meet = function () { return 0.5; }; return function () { AL.marks.meet = o; }; }]
  };
  Bt.proof = async function () {
    var out = {};
    for (var k in Bt.FAULTS) {
      var undo = Bt.FAULTS[k][1](), r;
      try { r = await Bt.run(); } finally { undo(); }
      var fails = (r.fails || []).map(function (f) { return f.label; });
      out[k] = { caught: fails.some(function (l) { return Bt.FAULTS[k][0].test(l); }), fails: fails };
    }
    return out;
  };
})(this);
