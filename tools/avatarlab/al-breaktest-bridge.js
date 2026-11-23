/**
 * MATCHED ANGLES MEET AT THE SWAP, WHATEVER ELSE IS MATCHED (owner's report, 11-23).
 * A turn from Left Quarter up to Upward popped at half way once both had also been matched to Frontal, which sits beside that path:
 * each drawing was pulled toward two meeting points at once and reached neither. This builds three plain squares on a pad, every pair
 * matched by its four corners, with the third square beside the path (never the nearest, so never shown), and checks that at the swap
 * each corner of the drawing on show is where the two that swap meet.
 *   await AL.breakTest.bridge.run()     the checks
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
    /* A to B: everything 20 to the right. A to C and B to C: 30 down. */
    function br(a, b, dx, dy) { return { a: a.id, b: b.id, meet: 0.5, pts: CORNERS.map(function (p) { return { a: [p[0], p[1]], b: [p[0] + dx, p[1] + dy] }; }) }; }
    var set = { id: AL.id('n'), name: 'Head', role: 'head', type: 'set', kids: kids, pick: { by: 'turn', depth: 0, fade: 0, rest: kids[0].id }, bridges: [br(kids[0], kids[1], 20, 0), br(kids[0], kids[2], 0, 30), br(kids[1], kids[2], 0, 30)] };
    ch.root.kids.push(set); AL.reindex(ch);
    return { ch: ch, set: set, A: kids[0], B: kids[1], C: kids[2] };
  }
  /* the corners of the drawing on show, as drawn this frame */
  function corners(app, art) {
    var it = app.out.items.filter(function (q) { return q.id === art.id && !q.ghost; })[0]; if (!it) return null;
    var nx = it.nx || Math.round(Math.sqrt(it.v.length / 2)) - 1, ny = it.v.length / 2 / (nx + 1) - 1, at = function (i, j) { var o = (j * (nx + 1) + i) * 2; return [it.v[o], it.v[o + 1]]; };
    return [at(0, 0), at(nx, 0), at(0, ny), at(nx, ny)];
  }
  Bt.run = async function () {
    var app = AL.app, R = K.mk(), ch0 = app.ch, bt0 = { x: app.bt.x, y: app.bt.y }, try0 = app.ballTry;
    try {
      var m = make(); app.setMode('build'); app.setCharacter(m.ch, { quiet: true }); app.ballTry = true;
      var cases = [['A', 0.49, m.A, [10, 0]], ['B', 0.51, m.B, [-10, 0]]];
      for (var i = 0; i < cases.length; i++) {
        var cs = cases[i], t = cs[1]; app.bt.x = t; app.bt.y = 0; await raf(4);
        var shown = app.out.picks[m.set.id], cn = corners(app, cs[2]);
        R.F('bridge: ' + cs[0] + ' is the drawing on show at ' + Math.round(t * 100) + '% (the square beside the path never is)', shown === cs[2].id, { shown: (AL.node(app.ch, shown) || {}).name });
        if (!cn) continue;
        var worst = 0; cn.forEach(function (p, k) { var want = [CORNERS[k][0] + cs[3][0], CORNERS[k][1] + cs[3][1]]; worst = Math.max(worst, Math.hypot(p[0] - want[0], p[1] - want[1])); });
        R.F('bridge: at the swap ' + cs[0] + '’s corners are where A and B meet, not pulled toward C beside the path', worst < 0.6, { worstPx: Math.round(worst * 100) / 100 });
      }
      /* and a turn straight to C still goes all the way to where A and C meet */
      app.bt.x = 0.5 * 0.49; app.bt.y = 0.6 * 0.49; await raf(4);
      var cA = corners(app, m.A), wC = 0; if (cA) cA.forEach(function (p, k) { wC = Math.max(wC, Math.hypot(p[0] - CORNERS[k][0], p[1] - (CORNERS[k][1] + 15))); });
      R.F('bridge: a turn straight toward C still meets C half way', app.out.picks[m.set.id] === m.A.id && cA && wC < 0.6, { worstPx: Math.round(wC * 100) / 100 });
    } catch (e) { R.F('THROWN', false, String(e && e.stack || e)); }
    app.bt.x = bt0.x; app.bt.y = bt0.y; app.ballTry = try0; app.setCharacter(ch0, { quiet: true }); await raf(2);
    return R.done ? R.done() : R;
  };
})(this);
