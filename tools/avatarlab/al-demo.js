/* AvatarLab: Pip, the built-in practice character.
 * Drawn here in code as an ordinary LAYERED file (folders and layer names, the way an artist would
 * hand one over) and then pushed through the same automatic rig as a real PSD. Nothing about Pip is
 * hand-rigged, so if Pip works the import works.
 */
(function (root) {
  'use strict';
  var AL = root.AL = root.AL || {};
  var INK = '#2b1830', BODY = '#ff8f5e', BELLY = '#fff0dc', PINK = '#ff5d8f', WHITE = '#fffdf8', DARK = '#5a1830';

  function layer(name, box, fn, vis) {
    var c = document.createElement('canvas'); c.width = box[2] - box[0]; c.height = box[3] - box[1];
    var g = c.getContext('2d'); g.translate(-box[0], -box[1]); g.lineJoin = 'round'; g.lineCap = 'round'; fn(g);
    var d = g.getImageData(0, 0, c.width, c.height).data, x0 = c.width, y0 = c.height, x1 = -1, y1 = -1;
    for (var y = 0; y < c.height; y++) for (var x = 0; x < c.width; x++) if (d[(y * c.width + x) * 4 + 3] > 8) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
    if (x1 < 0) return null;
    var t = document.createElement('canvas'); t.width = x1 - x0 + 1; t.height = y1 - y0 + 1; t.getContext('2d').drawImage(c, -x0, -y0);
    var id = AL.id('i'); AL.img.put(id, t, name);
    return { name: name, kind: 'layer', visible: vis !== false, opacity: 1, img: id, x: box[0] + x0, y: box[1] + y0 };
  }
  function group(name, kids, vis) { return { name: name, kind: 'group', visible: vis !== false, opacity: 1, kids: kids.filter(Boolean) }; }
  function ell(g, x, y, rx, ry, fill, lw) { g.beginPath(); g.ellipse(x, y, Math.max(0.5, rx), Math.max(0.5, ry), 0, 0, 7); if (fill) { g.fillStyle = fill; g.fill(); } if (lw) { g.strokeStyle = INK; g.lineWidth = lw; g.stroke(); } }

  function mouthShapes(mx, my, q, box) {
    function L(name, fn, vis) { return layer(name, box, fn, vis); }
    function open(g, w, hgt, teeth, tongue) {
      g.beginPath(); g.moveTo(mx - w * q, my - 3); g.lineTo(mx + w * q, my - 3); g.quadraticCurveTo(mx + w * q, my + hgt, mx, my + hgt); g.quadraticCurveTo(mx - w * q, my + hgt, mx - w * q, my - 3); g.closePath();
      g.fillStyle = DARK; g.fill(); g.save(); g.clip();
      if (tongue) ell(g, mx, my + hgt - 3, w * 0.55 * q, hgt * 0.4, PINK, 0);
      if (teeth) { g.fillStyle = WHITE; g.fillRect(mx - w * q, my - 4, w * 2 * q, 7); }
      g.restore(); g.strokeStyle = INK; g.lineWidth = 4; g.stroke();
    }
    function line(g, w, bend) { g.beginPath(); g.moveTo(mx - w * q, my); g.quadraticCurveTo(mx, my + bend, mx + w * q, my); g.strokeStyle = INK; g.lineWidth = 4; g.stroke(); }
    return [
      L('Neutral', function (g) { line(g, 13, 9); }),
      L('Aa', function (g) { open(g, 14, 21, false, true); }, false),
      L('Ee', function (g) { g.beginPath(); g.roundRect(mx - 17 * q, my - 5, 34 * q, 14, 7); g.fillStyle = WHITE; g.fill(); g.strokeStyle = INK; g.lineWidth = 4; g.stroke(); g.beginPath(); g.moveTo(mx - 14 * q, my + 2); g.lineTo(mx + 14 * q, my + 2); g.lineWidth = 1.5; g.stroke(); }, false),
      L('Oh', function (g) { ell(g, mx, my + 5, 9 * q, 12, DARK, 4); }, false),
      L('W-Oo', function (g) { ell(g, mx, my + 3, 5.5 * q, 6.5, DARK, 4); }, false),
      L('M', function (g) { line(g, 11, 1); }, false),
      L('F', function (g) { line(g, 12, 2); g.beginPath(); g.roundRect(mx - 7 * q, my - 6, 14 * q, 6, 2); g.fillStyle = WHITE; g.fill(); g.strokeStyle = INK; g.lineWidth = 3; g.stroke(); }, false),
      L('Smile', function (g) { open(g, 19, 17, true, true); }, false)
    ];
  }

  function headView(name, yaw, pitch) {
    var cx = 256, cy = 186, R = 90, fx = cx + yaw * 36, fy = cy + pitch * 26, q = 1 - Math.abs(yaw) * 0.22, box = [110, 50, 402, 310];
    function faceClip(g) { g.beginPath(); g.ellipse(cx, cy, R * (1 - Math.abs(yaw) * 0.04), R * 0.95, 0, 0, 7); }
    var kids = [];
    kids.push(layer('Nose', box, function (g) { ell(g, fx + yaw * 7, fy + 15 + pitch * 2, 7 * q, 5, INK, 0); }));
    kids.push(group('Mouth', mouthShapes(fx + yaw * 4, fy + 40 + pitch * 2, q, box)));
    [1, -1].forEach(function (s) {
      var far = s * yaw > 0 ? 1 - 0.36 * Math.abs(yaw) : 1, ex = fx + s * 31 * q, ey = fy - 12, rw = 15 * q * far, rh = 19;
      kids.push(layer(s > 0 ? 'Left Eyebrow' : 'Right Eyebrow', box, function (g) { g.beginPath(); g.moveTo(ex - rw - 1, ey - rh - 9 + s * 2); g.quadraticCurveTo(ex, ey - rh - 17, ex + rw + 1, ey - rh - 9 - s * 2); g.strokeStyle = INK; g.lineWidth = 5; g.stroke(); }));
      kids.push(group(s > 0 ? 'Left Eye' : 'Right Eye', [
        layer(s > 0 ? 'Left Blink' : 'Right Blink', box, function (g) { g.beginPath(); g.moveTo(ex - rw, ey + 1); g.quadraticCurveTo(ex, ey + 13, ex + rw, ey + 1); g.strokeStyle = INK; g.lineWidth = 4.5; g.stroke(); }, false),
        layer(s > 0 ? 'Left Pupil' : 'Right Pupil', box, function (g) { var pr = 8.5 * Math.min(1, rw / 12); ell(g, ex + yaw * 3, ey + 2 + pitch * 2, pr, pr * 1.08, INK, 0); ell(g, ex + yaw * 3 - pr * 0.32, ey - pr * 0.3 + pitch * 2, pr * 0.3, pr * 0.3, WHITE, 0); }),
        layer(s > 0 ? 'Left Eyeball' : 'Right Eyeball', box, function (g) { ell(g, ex, ey, rw, rh, WHITE, 4); })
      ]));
    });
    kids.push(layer('Hair tuft', [110, 10, 402, 310], function (g) { var bx = cx - yaw * 8, by = cy - R * 0.88 + pitch * 6, tx = cx + 18 - yaw * 26, ty = cy - R - 48 + pitch * 10; g.beginPath(); g.moveTo(bx, by); g.quadraticCurveTo(bx - 14, (by + ty) / 2 - 6, tx, ty); g.strokeStyle = INK; g.lineWidth = 17; g.stroke(); g.strokeStyle = BODY; g.lineWidth = 9; g.stroke(); }));
    kids.push(layer('Cheeks', box, function (g) { g.save(); faceClip(g); g.clip(); g.globalAlpha = 0.5; [1, -1].forEach(function (s) { ell(g, fx + s * 52 * q, fy + 20, 13 * q, 8, PINK, 0); }); g.restore(); }));
    kids.push(layer('Face', box, function (g) { faceClip(g); g.fillStyle = BODY; g.fill(); g.save(); g.clip(); ell(g, fx, fy + 30, 50 * q, 40, BELLY, 0); g.restore(); faceClip(g); g.strokeStyle = INK; g.lineWidth = 5; g.stroke(); }));
    kids.push(layer('Ears', box, function (g) { [1, -1].forEach(function (s) { var ex = cx + s * 66 - yaw * 22, ey = cy - 66 - pitch * 14, r = 27 * (1 - s * yaw * 0.16); ell(g, ex, ey, r, r, BODY, 5); ell(g, ex, ey, r * 0.5, r * 0.5, PINK, 0); }); }));
    return group(name, kids);
  }

  function arm(side) {
    var S = { x: 256 + side * 64, y: 296 }, kids = [], len = 96;
    function hand(g, W, dx, dy, pose) {
      var px = -dy, py = dx;
      function knob(k, along, r) { ell(g, W.x + dx * along + px * k, W.y + dy * along + py * k, r, r, BODY, 4); }
      if (pose === 'b') { knob(-10, 15, 6.5); knob(0, 18, 6.5); knob(10, 15, 6.5); }
      if (pose === 'c') { knob(-12, 12, 6); knob(-4, 18, 6); knob(5, 18, 6); knob(13, 12, 6); }
      knob(side * -15, pose === 'a' ? 2 : -2, 6.5);
      ell(g, W.x, W.y, 15, 15, BODY, 4.5);
    }
    [[-12, 'a'], [20, 'a'], [55, 'b'], [88, 'b'], [120, 'c'], [150, 'c'], [176, 'c']].forEach(function (d, i) {
      var th = d[0] * Math.PI / 180, dx = side * Math.sin(th), dy = Math.cos(th), W = { x: S.x + dx * len, y: S.y + dy * len };
      var C = { x: (S.x + W.x) / 2 + dy * side * 10, y: (S.y + W.y) / 2 - dx * side * 10 + 4 };
      kids.push(layer('0' + (i + 1), [S.x - 150, S.y - 150, S.x + 150, S.y + 150], function (g) {
        g.beginPath(); g.moveTo(S.x, S.y); g.quadraticCurveTo(C.x, C.y, W.x, W.y); g.strokeStyle = INK; g.lineWidth = 29; g.stroke(); g.strokeStyle = BODY; g.lineWidth = 20; g.stroke();
        hand(g, W, dx, dy, d[1]);
      }));
    });
    kids.push(layer('Forward', [S.x - 60, S.y - 40, S.x + 60, S.y + 80], function (g) {
      var W = { x: S.x + side * 8, y: S.y + 30 };
      g.beginPath(); g.moveTo(S.x, S.y); g.lineTo(W.x, W.y); g.strokeStyle = INK; g.lineWidth = 31; g.stroke(); g.strokeStyle = BODY; g.lineWidth = 22; g.stroke();
      ell(g, W.x, W.y, 22, 22, BODY, 4.5); [-10, 0, 10].forEach(function (k) { ell(g, W.x + k, W.y + 3, 5, 7, BELLY, 3); });
    }));
    return group(side > 0 ? 'Left Arm' : 'Right Arm', kids);
  }

  AL.demoDoc = function () {
    var GOLD = '#f5c542';
    function ring(g, x, y) { g.beginPath(); g.ellipse(x, y, 6, 10, 0, 0, 7); g.strokeStyle = INK; g.lineWidth = 7; g.stroke(); g.strokeStyle = GOLD; g.lineWidth = 3.5; g.stroke(); }
    var body = group('Body', [
      layer('Charm', [226, 318, 286, 376], function (g) { g.beginPath(); for (var k = 0; k < 10; k++) { var r = k % 2 ? 6.5 : 14, a = -Math.PI / 2 + k * Math.PI / 5; g.lineTo(256 + Math.cos(a) * r, 348 + Math.sin(a) * r); } g.closePath(); g.fillStyle = GOLD; g.fill(); g.strokeStyle = INK; g.lineWidth = 3.5; g.stroke(); }),
      layer('Chain link 2', [236, 300, 276, 344], function (g) { ring(g, 256, 322); }),
      layer('Chain link 1', [236, 282, 276, 326], function (g) { ring(g, 256, 304); }),
      layer('Torso', [150, 230, 362, 440], function (g) { ell(g, 256, 340, 78, 88, BODY, 5); g.save(); g.beginPath(); g.ellipse(256, 340, 78, 88, 0, 0, 7); g.clip(); ell(g, 256, 356, 50, 62, BELLY, 0); g.restore(); }),
      layer('Left Leg', [262, 400, 350, 480], function (g) { ell(g, 300, 446, 31, 20, BODY, 5); [-9, 3, 15].forEach(function (k) { g.beginPath(); g.moveTo(300 + k, 452); g.lineTo(300 + k, 463); g.strokeStyle = INK; g.lineWidth = 3; g.stroke(); }); }),
      layer('Right Leg', [162, 400, 250, 480], function (g) { ell(g, 212, 446, 31, 20, BODY, 5); [-15, -3, 9].forEach(function (k) { g.beginPath(); g.moveTo(212 + k, 452); g.lineTo(212 + k, 463); g.strokeStyle = INK; g.lineWidth = 3; g.stroke(); }); }),
      layer('Tail', [290, 290, 480, 450], function (g) { g.beginPath(); g.moveTo(316, 398); g.quadraticCurveTo(384, 434, 424, 372); g.strokeStyle = INK; g.lineWidth = 25; g.stroke(); g.strokeStyle = BODY; g.lineWidth = 16; g.stroke(); ell(g, 427, 364, 17, 17, PINK, 4.5); })
    ]);
    var head = group('Head', [headView('Front', 0, 0), headView('Left', -1, 0), headView('Up', 0, -1), headView('Down', 0, 1), headView('Up Left', -1, -1), headView('Down Left', -1, 1)]);
    return { name: 'Pip', w: 512, h: 512, kids: [group('Pip', [arm(1), arm(-1), head, body])] };
  };
  AL.demo = function () {
    var ch = AL.autoRig(AL.demoDoc());
    ch.name = 'Pip'; ch.root.name = 'Pip'; ch.settings.facing = 'front'; ch.demo = true;
    return ch;
  };
})(typeof self !== 'undefined' ? self : this);
