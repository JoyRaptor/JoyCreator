/* Makes topps-mask.patch.json and topps-mask.golden.json: the web pilot's solver drawing Topps WEARING THE FACE MASK (FACE-2), for
 * the phone's solver to match (tools/jvm-harness/CharacterSolverTest.java, "topps mask"). Run in the pilot page (tools/avatarlab,
 * served by serve.mjs) with the built-in Topps open, in the console or over CDP; it returns { patch, golden }.
 * The mask: the pilot's own guess on every drawing of the head (AL.mask.create). A pose made by hand (two points moved on the
 * front drawing, one on the quarter) and a swap (while "open" is strong the head's first inner set shows its last drawing).
 * The patch is only the head set's mask landmarks (marks['']) and its face data: the test puts them on its own Topps.
 */
(async function () {
  for (var i = 0; i < 80 && !(self.AL && AL.app && AL.app.ch); i++) await new Promise(function (r) { setTimeout(r, 250); });
  var ch = JSON.parse(JSON.stringify(AL.app.ch));
  var set = AL.mask.headSet(ch);
  AL.mask.create(set);
  var mk = AL.mask.piece(set), angles = AL.mask.angles(set);
  function at(k) { return k.at || [0, 0]; }
  var front = angles.filter(function (k) { return Math.abs(at(k)[0]) < 0.01 && Math.abs(at(k)[1]) < 0.01; })[0] || angles[0];
  var quarter = angles.filter(function (k) { return Math.abs(at(k)[0] - 0.5) < 0.01 && Math.abs(at(k)[1]) < 0.01; })[0] || angles[1];
  var withFace = mk.pts.filter(function (p) { return p.face && p.face.length; });
  var a = withFace[0], b = withFace[Math.min(5, withFace.length - 1)];
  var f = set.face, posed = {};
  posed[front.id] = {}; posed[front.id][a.n] = [6, -4]; posed[front.id][b.n] = [-5, 3];
  posed[quarter.id] = {}; posed[quarter.id][a.n] = [4, -3];
  f.posed = { grin: posed };
  function sets(n, out) { if (n.type === 'set' && n !== set && (n.kids || []).length > 1) out.push(n); (n.kids || []).forEach(function (k) { sets(k, out); }); return out; }
  var inner = sets(front, []);                                       // (a set inside the front drawing: the one drawn at rest)
  if (inner.length) { var ks = inner[0].kids, rest = inner[0].pick && inner[0].pick.rest, alt = ks.filter(function (k) { return k.id !== rest; });
    f.swapped = { open: {} }; f.swapped.open[inner[0].id] = (alt[alt.length - 1] || ks[ks.length - 1]).id; }
  function base() { var S = AL.restSignals(); S.pose = {}; S.tone = {}; return S; }
  function cs(name, fn) { var S = base(); fn(S); return { name: name, signals: S }; }
  var A = 'mk.' + a.n, B = 'mk.' + b.n;
  var cases = [
    cs('rest', function () { }),
    cs('mask: two points moved, front', function (S) { S[A + '.x'] = 0.12; S[A + '.y'] = -0.1; S[B + '.y'] = 0.15; }),
    cs('mask: two points moved, turned', function (S) { S.turnX = 0.5; S[A + '.x'] = 0.12; S[B + '.y'] = 0.15; }),
    cs('mask: mid-turn (the turn bend carries the mask)', function (S) { S.turnX = 0.25; S[A + '.x'] = 0.12; S[B + '.y'] = 0.15; }),
    cs('mask: a pose at 0.8', function (S) { S['mp.grin'] = 0.8; }),
    cs('mask: a pose swaps a drawing', function (S) { S['mp.open'] = 0.9; })
  ];
  var golden = AL.golden.make(ch, cases);
  golden.note = 'Topps wearing the face mask (FACE-2); the mask and face data are in topps-mask.patch.json (made by make-topps-mask.js)';
  return { patch: { format: 'joycreator.mask-patch/1', head: set.id, marks: set.marks[''], face: set.face, points: [a.n, b.n], front: front.id, quarter: quarter.id, swapSet: inner.length ? inner[0].id : null }, golden: golden };
})()
