/* AvatarLab: the face camera read the way the phone reads it (job FACE-1).
 * A line-for-line port of the phone's face engine (app/src/main/java/com/fadcam/ui/faditor/avatar/face/:
 * FaceMapper, FaceCalibration, FaceStage), so one face drives a character the same in the pilot and on
 * the phone. tools/jvm-harness/face_parity.mjs checks the two against each other.
 *
 * What it adds to al-inputs.js's own reading: everything measured from YOUR neutral face (the first still
 * half-second, or AL.face.neutral()), two sides kept apart (a wink, one raised brow, a half smile),
 * continuous blinks that still land, the jaw apart from the lips, one-sided lips, lean, and a lost face
 * that holds a moment then eases to rest instead of freezing.
 *
 * Hooked in (11-22, FACE-1, the owner's go-ahead for face pins): index.html loads this before al-inputs.js; al-inputs.js
 * P.watch hands every result to AL.face.pilotFace(r, cam, t) (the face, or null to ease a lost face to rest), and P.step
 * moves the two brows apart. "Re-centre" in Perform is AL.face.neutral(cam). Face pins (al-facepins.js) read the points.
 * pilotFace returns what al-inputs.js reads (mouth, blinkL/R, brow, lookX/Y, smile, lidUp, lidLow, lidTilt, turnX/Y, roll)
 * plus every new signal (browL/R, jaw, jawX, lipUpL ..., leanX/Y, near, face) and the watched points ('pt.<i>.x' / '.y').
 * Phone: com.fadcam.ui.faditor.avatar.face.*.
 */
(function (root) {
  'use strict';
  var AL = root.AL = root.AL || {};
  var F = AL.face = {};

  F.SHAPES = ['Neutral', 'Aa', 'Ee', 'Oh', 'W-Oo', 'M', 'F', 'L', 'S', 'D', 'R', 'Uh', 'Smile', 'Surprised'];
  F.HEAD_YAW = 'headYawDeg'; F.HEAD_PITCH = 'headPitchDeg'; F.HEAD_ROLL = 'headRollDeg';
  F.FACE_X = 'faceX'; F.FACE_Y = 'faceY'; F.FACE_SIZE = 'faceSize';
  var TURN_DEG = 32, NOD_DEG = 24, ROLL_DEG = 35, LEGACY_YAW_DEG = 45, LEGACY_PITCH_DEG = 30, MIN_SPAN = 0.25;
  var STEP = { vis: 1, face: 1 };
  F.ALL = ['turnX', 'turnY', 'roll', 'yaw', 'pitch', 'lookX', 'lookY', 'blinkL', 'blinkR', 'squintL', 'squintR', 'wideL', 'wideR',
    'browL', 'browR', 'browInner', 'sneer', 'lidUp', 'lidLow', 'lidTilt', 'mouth', 'jawOpen', 'jaw', 'jawX', 'jawFwd', 'smileL', 'smileR',
    'frownL', 'frownR', 'lipUpL', 'lipUpR', 'lipDownL', 'lipDownR', 'pucker', 'funnel', 'mouthWide', 'press', 'mouthX', 'cheekPuff',
    'leanX', 'leanY', 'near', 'vis', 'face'];

  function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
  function amt(v) { return clamp(v, 0, 1); }
  function sgn(v) { return clamp(v, -1, 1); }
  function smoothstep(a, b, x) { var t = clamp((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); }
  function num(v) { return v == null || v !== v ? 0 : v; }
  F.rest = function () { var o = {}; F.ALL.forEach(function (n) { o[n] = 0; }); return o; };

  /* ── calibration: neutral and directions (FaceCalibration.java) ── */
  F.calibration = function (o) {
    o = o || {};
    var s = o.sign || {};
    return { rest: Object.assign({}, o.rest || {}), sign: { yaw: s.yaw < 0 ? -1 : 1, pitch: s.pitch < 0 ? -1 : 1, roll: s.roll < 0 ? -1 : 1, lean: s.lean > 0 ? 1 : -1 },
      swapSides: !!o.swapSides, neutralSet: o.neutralSet != null ? !!o.neutralSet : !!(o.rest && Object.keys(o.rest).length),
      points: o.points && o.points.length >= (F.POINT_COUNT - 10) * 3 ? Float32Array.from(o.points) : null };
  };
  function rest(cal, k) { return num(cal.rest[k]); }
  F.amount = function (cal, k, v) { var r = rest(cal, k), span = Math.max(MIN_SPAN, 1 - r); return clamp((v - r) / span, 0, 1); };
  function headDeg(cal, axis, deg) {
    var s = axis === F.HEAD_YAW ? cal.sign.yaw : axis === F.HEAD_PITCH ? cal.sign.pitch : cal.sign.roll;
    return (deg - rest(cal, axis)) * s;
  }
  function lean(cal, fx, fy, size) {
    var size0 = rest(cal, F.FACE_SIZE);
    if (!cal.neutralSet || size0 <= 0.02 || size <= 0.02) return null;
    return [(fx - rest(cal, F.FACE_X)) * cal.sign.lean / size0, (fy - rest(cal, F.FACE_Y)) / size0, size / size0 - 1];
  }

  /** A still stretch of face becomes the neutral (FaceCalibration.Capture). */
  F.Capture = function (base, frames) { this.base = base; this.need = Math.max(1, frames); this.restart(); };
  F.Capture.prototype.restart = function () { this.sum = {}; this.n = 0; this.pSum = null; this.pN = 0; };
  /** One tracked frame with its face points (normalised, or null): FaceCalibration.Capture.add(raw, points). */
  F.Capture.prototype.addWithPoints = function (raw, pts) {
    var done = this.add(raw);
    if (this.n === 1) { this.pSum = null; this.pN = 0; }
    if (pts) {
      if (!this.pSum || this.pSum.length !== pts.length) { this.pSum = new Float64Array(pts.length); this.pN = 0; }
      for (var i = 0; i < pts.length; i++) this.pSum[i] += pts[i];
      this.pN++;
    }
    return done;
  };
  F.Capture.prototype.add = function (raw) {
    var yaw = num(raw[F.HEAD_YAW]), pitch = num(raw[F.HEAD_PITCH]);
    if (this.n === 0) { this.minY = this.maxY = yaw; this.minP = this.maxP = pitch; }
    else {
      this.minY = Math.min(this.minY, yaw); this.maxY = Math.max(this.maxY, yaw); this.minP = Math.min(this.minP, pitch); this.maxP = Math.max(this.maxP, pitch);
      if (this.maxY - this.minY > 6 || this.maxP - this.minP > 6) { this.restart(); return this.add(raw); }
    }
    for (var k in raw) { var v = raw[k]; if (v == null || v !== v) continue; this.sum[k] = (this.sum[k] || 0) + v; }
    this.n++;
    return this.n >= this.need;
  };
  F.Capture.prototype.result = function () {
    var c = F.calibration(this.base); c.rest = {};
    for (var k in this.sum) c.rest[k] = this.sum[k] / this.n;
    c.neutralSet = true;
    if (this.pSum && this.pN > 0) { var pts = new Float32Array(this.pSum.length); for (var i = 0; i < pts.length; i++) pts[i] = this.pSum[i] / this.pN; c.points = pts; }
    return c;
  };

  /** One prompt of the direction check (yaw / pitch / roll / lean / eye), as on the phone. */
  F.DirectionCheck = function (what, cal) { this.what = what; this.cal = cal; this.answer = 0; };
  F.DirectionCheck.prototype.add = function (raw) {
    if (this.answer) return true;
    if (this.what === 'eye') {
      var l = F.amount(this.cal, 'eyeBlinkLeft', num(raw.eyeBlinkLeft)), r = F.amount(this.cal, 'eyeBlinkRight', num(raw.eyeBlinkRight));
      if (l - r > 0.35) this.answer = 1; else if (r - l > 0.35) this.answer = -1;
    } else {
      if (!(this.what in raw)) return false;
      var moved = num(raw[this.what]) - rest(this.cal, this.what);
      if (Math.abs(moved) >= (this.what === F.FACE_X ? 0.05 : 12)) this.answer = moved > 0 ? 1 : -1;
    }
    return !!this.answer;
  };
  F.DirectionCheck.prototype.apply = function () {
    if (!this.answer) return this.cal;
    var c = F.calibration(this.cal);
    if (this.what === 'eye') c.swapSides = this.answer < 0;
    else if (this.what === F.HEAD_YAW) c.sign.yaw = this.answer; else if (this.what === F.HEAD_PITCH) c.sign.pitch = this.answer;
    else if (this.what === F.HEAD_ROLL) c.sign.roll = this.answer; else if (this.what === F.FACE_X) c.sign.lean = this.answer;
    return c;
  };

  /* ── the mapping (FaceMapper.java, line for line) ── */
  function shape(open, smile, pucker, funnel, wide, press, inner) {
    if (open < 0.1) return press > 0.45 ? 'M' : smile > 0.5 ? 'Smile' : pucker > 0.5 ? 'W-Oo' : 'Neutral';
    if (pucker > 0.45) return 'W-Oo';
    if (funnel > 0.4) return 'Oh';
    if (open > 0.55 && inner > 0.5) return 'Surprised';
    if (wide > 0.35 || smile > 0.4) return 'Ee';
    return 'Aa';
  }
  function blink(closure, lookDown) { return smoothstep(0.12, 0.8, closure - 0.4 * lookDown); }

  F.map = function (raw, cal, tun) {
    tun = tun || {};
    var mirror = !!tun.mirror, flip = mirror ? -1 : 1, g = function (k, d) { return tun[k] == null ? (d == null ? 1 : d) : tun[k]; };
    var head = g('head'), eyes = g('eyes'), brows = g('brows'), mo = g('mouth'), sync = tun.blinkSync !== false;
    function rw(k) { return num(raw[k]); }
    function a(k) { return F.amount(cal, k, rw(k)); }
    function pR(b) { return a(b + (cal.swapSides ? 'Right' : 'Left')); }
    function pL(b) { return a(b + (cal.swapSides ? 'Left' : 'Right')); }
    function cL(b) { return mirror ? pL(b) : pR(b); }
    function cR(b) { return mirror ? pR(b) : pL(b); }
    var o = {};
    var yawR = headDeg(cal, F.HEAD_YAW, rw(F.HEAD_YAW)), down = headDeg(cal, F.HEAD_PITCH, rw(F.HEAD_PITCH)), tiltR = headDeg(cal, F.HEAD_ROLL, rw(F.HEAD_ROLL));
    o.turnX = sgn(flip * yawR / TURN_DEG * head); o.turnY = sgn(down / NOD_DEG * head); o.roll = sgn(flip * tiltR / ROLL_DEG * head);
    o.yaw = sgn(flip * yawR / LEGACY_YAW_DEG * head); o.pitch = sgn(-down / LEGACY_PITCH_DEG * head);
    var ln = (F.FACE_SIZE in raw) ? lean(cal, rw(F.FACE_X), rw(F.FACE_Y), rw(F.FACE_SIZE)) : null;
    o.leanX = ln ? sgn(flip * ln[0] * 1.5 * head) : 0; o.leanY = ln ? sgn(ln[1] * 1.5 * head) : 0; o.near = ln ? sgn(ln[2] * 2.5 * head) : 0;

    var lookDown = (a('eyeLookDownLeft') + a('eyeLookDownRight')) / 2, lookUp = (a('eyeLookUpLeft') + a('eyeLookUpRight')) / 2;
    var towardR = ((pR('eyeLookOut') - pR('eyeLookIn')) + (pL('eyeLookIn') - pL('eyeLookOut'))) / 2;
    o.lookX = sgn(flip * towardR * 1.6 * eyes); o.lookY = sgn((lookDown - lookUp) * 1.6 * eyes);
    var cl = cL('eyeBlink'), cr = cR('eyeBlink');
    if (sync) { var avg = (cl + cr) / 2, w = smoothstep(0.15, 0.35, Math.abs(cl - cr)); cl = avg + (cl - avg) * w; cr = avg + (cr - avg) * w; }
    o.blinkL = amt(blink(cl, lookDown)); o.blinkR = amt(blink(cr, lookDown));
    o.squintL = amt((cL('eyeSquint') + 0.5 * cL('cheekSquint')) * eyes); o.squintR = amt((cR('eyeSquint') + 0.5 * cR('cheekSquint')) * eyes);
    o.wideL = amt(cL('eyeWide') * 1.3 * eyes); o.wideR = amt(cR('eyeWide') * 1.3 * eyes);

    var inner = a('browInnerUp');
    o.browL = sgn((0.7 * cL('browOuterUp') + 0.5 * inner - 1.2 * cL('browDown')) * brows);
    o.browR = sgn((0.7 * cR('browOuterUp') + 0.5 * inner - 1.2 * cR('browDown')) * brows);
    o.browInner = amt(inner * brows);
    o.sneer = amt((a('noseSneerLeft') + a('noseSneerRight')) / 2 * 1.4 * brows);
    o.lidUp = clamp(Math.min(a('eyeBlinkLeft'), a('eyeBlinkRight')) * 1.5 * brows, 0, 0.8);
    o.lidLow = amt(((a('eyeSquintLeft') + a('eyeSquintRight')) * 0.9 + (a('cheekSquintLeft') + a('cheekSquintRight')) * 0.6) * brows);
    o.lidTilt = sgn(((a('browDownLeft') + a('browDownRight')) * 1.2 - inner * 1.4) * brows);

    o.jaw = amt(a('jawOpen') * 1.7 * mo);
    o.jawX = sgn(flip * (pR('jaw') - pL('jaw')) * 1.5 * mo);
    o.jawFwd = amt(a('jawForward') * 1.5 * mo);
    var m = amt((a('jawOpen') - a('mouthClose')) * 1.7 * mo);
    o.mouth = m; o.jawOpen = m;
    o.lipUpL = amt(cL('mouthUpperUp') * 1.4 * mo); o.lipUpR = amt(cR('mouthUpperUp') * 1.4 * mo);
    o.lipDownL = amt(cL('mouthLowerDown') * 1.4 * mo); o.lipDownR = amt(cR('mouthLowerDown') * 1.4 * mo);
    o.smileL = amt(cL('mouthSmile') * 1.2 * mo); o.smileR = amt(cR('mouthSmile') * 1.2 * mo);
    o.frownL = amt(cL('mouthFrown') * 1.5 * mo); o.frownR = amt(cR('mouthFrown') * 1.5 * mo);
    o.pucker = amt(a('mouthPucker') * 1.2 * mo); o.funnel = amt(a('mouthFunnel') * 1.4 * mo);
    o.mouthWide = amt((a('mouthStretchLeft') + a('mouthStretchRight')) / 2 * 1.5 * mo);
    o.press = amt(((a('mouthPressLeft') + a('mouthPressRight')) / 2 * 1.2 + Math.max(a('mouthRollLower'), a('mouthRollUpper')) * 0.8) * mo);
    o.mouthX = sgn(flip * (pR('mouth') - pL('mouth')) * 1.5 * mo);
    o.cheekPuff = amt(a('cheekPuff') * 1.3 * mo);
    o.vis = F.SHAPES.indexOf(shape(m, (o.smileL + o.smileR) / 2, o.pucker, o.funnel, o.mouthWide, o.press, inner));
    o.face = 1;
    return o;
  };

  /* ── face points for face pins (FacePoints.java, line for line) ──
     A point's movement from the neutral face with the head's turn, tilt, place and distance taken out:
     the head's own frame is fitted to points that do not move with expression (Horn's method). */
  F.POINT_COUNT = 478;
  var STEADY = [10, 151, 9, 8, 168, 6, 197, 195, 5, 4, 1, 33, 263, 133, 362, 234, 454, 127, 356, 21, 251, 54, 284];
  /** [mesh index, the person's side (1 right, -1 left, 0 middle), name]: the face map's named points. */
  F.NAMED = [[61, 1, 'mouth corner'], [291, -1, 'mouth corner'],
    [0, 0, 'upper lip'], [17, 0, 'lower lip'], [13, 0, 'upper lip, inside'], [14, 0, 'lower lip, inside'],
    [37, 1, 'upper lip side'], [267, -1, 'upper lip side'], [84, 1, 'lower lip side'], [314, -1, 'lower lip side'],
    [159, 1, 'upper lid'], [145, 1, 'lower lid'], [386, -1, 'upper lid'], [374, -1, 'lower lid'],
    [107, 1, 'brow, inner'], [105, 1, 'brow, middle'], [70, 1, 'brow, outer'],
    [336, -1, 'brow, inner'], [334, -1, 'brow, middle'], [300, -1, 'brow, outer'],
    [98, 1, 'nostril'], [327, -1, 'nostril'], [1, 0, 'nose tip'],
    [205, 1, 'cheek'], [425, -1, 'cheek'], [152, 0, 'chin'], [172, 1, 'jaw corner'], [397, -1, 'jaw corner']];
  F.normalizePoints = function (xyz) {
    var n = (xyz.length / 3) | 0, cx = 0, cy = 0, cz = 0, ss = 0, i;
    if (n < F.POINT_COUNT - 10) return null;
    STEADY.forEach(function (j) { cx += xyz[j * 3]; cy += xyz[j * 3 + 1]; cz += xyz[j * 3 + 2]; });
    cx /= STEADY.length; cy /= STEADY.length; cz /= STEADY.length;
    STEADY.forEach(function (j) { var dx = xyz[j * 3] - cx, dy = xyz[j * 3 + 1] - cy, dz = xyz[j * 3 + 2] - cz; ss += dx * dx + dy * dy + dz * dz; });
    var s = Math.sqrt(ss / STEADY.length);
    if (s < 1e-6) return null;
    var out = new Float32Array(n * 3);
    for (i = 0; i < n; i++) { out[i * 3] = (xyz[i * 3] - cx) / s; out[i * 3 + 1] = (xyz[i * 3 + 1] - cy) / s; out[i * 3 + 2] = (xyz[i * 3 + 2] - cz) / s; }
    return out;
  };
  F.pointOffsets = function (rest, now, chosen, side) {
    if (!rest || !now || rest.length !== now.length) return null;
    var r = pointRotation(rest, now), out = new Float32Array(chosen.length * 2);
    for (var k = 0; k < chosen.length; k++) {
      var i = chosen[k];
      if (i < 0 || i * 3 + 2 >= now.length) continue;
      var x = now[i * 3], y = now[i * 3 + 1], z = now[i * 3 + 2];
      var ux = r[0] * x + r[3] * y + r[6] * z, uy = r[1] * x + r[4] * y + r[7] * z;
      out[k * 2] = (ux - rest[i * 3]) * side;
      out[k * 2 + 1] = uy - rest[i * 3 + 1];
    }
    return out;
  };
  function pointRotation(a, b) {
    var sxx = 0, sxy = 0, sxz = 0, syx = 0, syy = 0, syz = 0, szx = 0, szy = 0, szz = 0;
    STEADY.forEach(function (i) {
      var ax = a[i * 3], ay = a[i * 3 + 1], az = a[i * 3 + 2], bx = b[i * 3], by = b[i * 3 + 1], bz = b[i * 3 + 2];
      sxx += ax * bx; sxy += ax * by; sxz += ax * bz; syx += ay * bx; syy += ay * by; syz += ay * bz; szx += az * bx; szy += az * by; szz += az * bz;
    });
    var q = topEigenvector([[sxx + syy + szz, syz - szy, szx - sxz, sxy - syx], [syz - szy, sxx - syy - szz, sxy + syx, szx + sxz],
      [szx - sxz, sxy + syx, -sxx + syy - szz, syz + szy], [sxy - syx, szx + sxz, syz + szy, -sxx - syy + szz]]);
    var w = q[0], x = q[1], y = q[2], z = q[3];
    return [1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y), 2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
      2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)];
  }
  function topEigenvector(m) {
    var a = m.map(function (r) { return r.slice(); }), v = [[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]], p, q, k;
    for (var sweep = 0; sweep < 60; sweep++) {
      var off = 0;
      for (p = 0; p < 4; p++) for (q = p + 1; q < 4; q++) off += a[p][q] * a[p][q];
      if (off < 1e-22) break;
      for (p = 0; p < 4; p++) {
        for (q = p + 1; q < 4; q++) {
          if (Math.abs(a[p][q]) < 1e-30) continue;
          var theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
          var t = Math.sign(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
          if (theta === 0) t = 1;
          var c = 1 / Math.sqrt(t * t + 1), s = t * c;
          for (k = 0; k < 4; k++) { var akp = a[k][p], akq = a[k][q]; a[k][p] = c * akp - s * akq; a[k][q] = s * akp + c * akq; }
          for (k = 0; k < 4; k++) { var apk = a[p][k], aqk = a[q][k]; a[p][k] = c * apk - s * aqk; a[q][k] = s * apk + c * aqk; }
          for (k = 0; k < 4; k++) { var vkp = v[k][p], vkq = v[k][q]; v[k][p] = c * vkp - s * vkq; v[k][q] = s * vkp + c * vkq; }
        }
      }
    }
    var best = 0;
    for (var i = 1; i < 4; i++) if (a[i][i] > a[best][best]) best = i;
    var e = [v[0][best], v[1][best], v[2][best], v[3][best]], len = Math.sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2] + e[3] * e[3]);
    return e.map(function (x) { return x / len; });
  }
  /** All the face's points from a MediaPipe (JS) result, x, y, z each, y in width units (tall = height / width). */
  F.facePoints = function (r, tall) {
    if (!r || !r.faceLandmarks || !r.faceLandmarks.length) return null;
    var p = r.faceLandmarks[0], out = new Float32Array(p.length * 3);
    for (var i = 0; i < p.length; i++) { out[i * 3] = p[i].x; out[i * 3 + 1] = p[i].y * tall; out[i * 3 + 2] = p[i].z; }
    return out;
  };

  /* ── the stage: neutral, lost and found, gaze through blinks, steady sheet mouths (FaceStage.java) ── */
  var HOLD = 0.35, EASE_OUT = 0.8, EASE_IN = 0.3, NEUTRAL_FRAMES = 8;
  function blend(a, b, k) {
    var o = {};
    function put(n) { var av = num(a[n]), bv = num(b[n]); o[n] = STEP[n] ? (k >= 0.5 ? bv : av) : av + (bv - av) * k; }
    F.ALL.forEach(put);
    for (var n in a) if (n.slice(0, 3) === 'pt.') put(n);        // face pins rest at 0
    for (var m in b) if (m.slice(0, 3) === 'pt.') put(m);
    return o;
  }
  function sm(k) { return k * k * (3 - 2 * k); }
  F.Stage = function (cal, tun) {
    this.cal = cal || F.calibration(); this.tun = tun || {}; this.autoNeutral = true; this.wantNeutral = false;
    this.lastTracked = F.rest(); this.lastOut = F.rest(); this.cross = null; this.crossK = 1; this.presence = 0;
    this.lastT = NaN; this.lostSince = NaN; this.gx = 0; this.gy = 0; this.vis = 0; this.visPending = 0; this.visN = 0; this.found = false;
  };
  var S = F.Stage.prototype;
  S.requestNeutral = function () { this.wantNeutral = true; };
  S.check = function (what) { this.checking = new F.DirectionCheck(what, this.cal); };
  /** Face pins: put out the movement of these face points (mesh indices). */
  S.watchPoints = function (list) { this.watched = (list || []).slice(); };
  S.process = function (raw, t, xyz) {
    var pts = raw && xyz ? F.normalizePoints(xyz) : null;
    var dt = this.lastT !== this.lastT ? 0 : clamp(t - this.lastT, 0, 0.5), target;
    this.lastT = t;
    if (raw) {
      this.found = true; this.calibrate(raw, pts);
      if (this.autoNeutral && !this.cal.neutralSet) {      // the first half-second: wait at rest for the neutral
        if (this.lostSince !== this.lostSince) this.lostSince = t;
        this.lastOut = F.rest(); return F.rest();
      }
      if (this.lostSince === this.lostSince) { this.cross = Object.assign({}, this.lastOut); this.crossK = 0; this.lostSince = NaN; }
      var m = F.map(raw, this.cal, this.tun);
      var w = this.watched || [];
      if (w.length) {
        var off = F.pointOffsets(this.cal.points, pts, w, (this.tun.mirror ? -1 : 1) * this.cal.sign.lean);
        for (var q = 0; q < w.length; q++) { m['pt.' + w[q] + '.x'] = off ? off[q * 2] : 0; m['pt.' + w[q] + '.y'] = off ? off[q * 2 + 1] : 0; }
      }
      if (Math.max(m.blinkL, m.blinkR) > 0.55) { m.lookX = this.gx; m.lookY = this.gy; } else { this.gx = m.lookX; this.gy = m.lookY; }
      if (m.vis === this.vis) this.visN = 0; else if (m.vis === this.visPending) { if (++this.visN >= 2) { this.vis = m.vis; this.visN = 0; } } else { this.visPending = m.vis; this.visN = 1; }
      m.vis = this.vis;
      this.lastTracked = m;
      this.presence = Math.min(1, this.presence + (dt > 0 ? dt / EASE_IN : 0));
      if (this.presence === 0 && dt === 0) this.presence = 1;
      target = blend(F.rest(), m, this.presence);
      if (this.cross) { this.crossK = Math.min(1, this.crossK + dt / EASE_IN); target = blend(this.cross, target, sm(this.crossK)); if (this.crossK >= 1) this.cross = null; }
    } else {
      this.found = false; if (this.capture) this.capture.restart();
      if (this.lostSince !== this.lostSince) { this.lostSince = t; this.cross = null; }
      if (t - this.lostSince >= HOLD) this.presence = Math.max(0, this.presence - (dt > 0 ? dt / EASE_OUT : 0));
      target = blend(F.rest(), this.lastTracked, sm(this.presence));
      target.vis = this.presence > 0.5 ? this.lastTracked.vis : 0;
    }
    target.face = this.presence;
    this.lastOut = target;
    return Object.assign({}, target);
  };
  S.calibrate = function (raw, pts) {
    if (this.checking) { if (this.checking.add(raw)) { this.cal = this.checking.apply(); this.lastCheck = this.checking; this.checking = null; } return; }
    if (!(this.wantNeutral || (this.autoNeutral && !this.cal.neutralSet))) { this.capture = null; return; }
    if (!this.capture) this.capture = new F.Capture(this.cal, NEUTRAL_FRAMES);
    if (this.capture.addWithPoints(raw, pts)) { this.cal = this.capture.result(); this.capture = null; this.wantNeutral = false; F.save(this.cal); }
  };

  /* ── a MediaPipe (JS) FaceLandmarker result to the raw face ── */
  var OVAL = [10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109];
  F.rawFromResult = function (r) {
    if (!r || !r.faceBlendshapes || !r.faceBlendshapes.length || !r.faceLandmarks || !r.faceLandmarks.length) return null;
    var raw = {};
    r.faceBlendshapes[0].categories.forEach(function (q) { if (q.categoryName && q.categoryName !== '_neutral') raw[q.categoryName] = q.score; });
    var mm = r.facialTransformationMatrixes && r.facialTransformationMatrixes[0] && r.facialTransformationMatrixes[0].data, D = 180 / Math.PI;
    if (mm) {   // the pilot's own extraction (al-inputs.js watch), in degrees with its signs
      raw[F.HEAD_YAW] = Math.atan2(mm[8], mm[10]) * D;
      raw[F.HEAD_PITCH] = -Math.asin(clamp(-mm[9], -1, 1)) * D;
      raw[F.HEAD_ROLL] = -Math.atan2(mm[1], mm[5]) * D;
    }
    var p = r.faceLandmarks[0], x0 = 1, x1 = 0, y0 = 1, y1 = 0;
    OVAL.forEach(function (i) { var q = p[i]; if (!q) return; x0 = Math.min(x0, q.x); x1 = Math.max(x1, q.x); y0 = Math.min(y0, q.y); y1 = Math.max(y1, q.y); });
    if (x1 > x0) { raw[F.FACE_X] = (x0 + x1) / 2; raw[F.FACE_Y] = (y0 + y1) / 2; raw[F.FACE_SIZE] = x1 - x0; }
    return raw;
  };

  /* ── the pilot's face camera (al-inputs.js cam object) ── */
  F.KEY = 'al.face.cal';
  F.load = function () { try { return F.calibration(JSON.parse(root.localStorage.getItem(F.KEY) || 'null') || {}); } catch (e) { return F.calibration(); } };
  F.save = function (cal) {
    var o = Object.assign({}, cal);
    if (cal.points) o.points = Array.prototype.map.call(cal.points, function (v) { return Math.round(v * 10000) / 10000; });
    try { root.localStorage.setItem(F.KEY, JSON.stringify(o)); } catch (e) { /* private mode: kept for this visit */ }
  };
  /** One tap: the next still half-second of the face becomes neutral. */
  F.neutral = function (cam) { if (cam && cam._faceStage) cam._faceStage.requestNeutral(); };
  F.pilotFace = function (r, cam, t) {
    var st = cam._faceStage || (cam._faceStage = new F.Stage(F.load(), {}));
    st.tun.mirror = !!cam.swap;
    var v = cam.v, tall = v && v.videoWidth ? v.videoHeight / v.videoWidth : 0.75;
    var o = st.process(F.rawFromResult(r), t, F.facePoints(r, tall));
    o.brow = (o.browL + o.browR) / 2;
    o.smile = (o.smileL + o.smileR) / 2;
    return o;
  };
})(typeof self !== 'undefined' ? self : this);
