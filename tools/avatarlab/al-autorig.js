/* AvatarLab: the automatic rig. Layer names in, a moving character out.
 *
 * Takes a plain layer tree (from a PSD, from a cut sheet, from loose pictures) and:
 *   1. works out what each layer IS from its name (Character Animator names and everyday ones),
 *   2. turns folders into the right kind of set (head angles, arm fans, mouth shapes),
 *   3. GUESSES the pins from the pixels, so nobody places 3 pins on 16 arm drawings by hand.
 * Every guess is ordinary data afterwards: visible, draggable, one tap to redo.
 */
(function (root) {
  'use strict';
  var AL = root.AL = root.AL || {};
  function hyp(x, y) { return Math.sqrt(x * x + y * y); }
  function lc(s) { return (s || '').toLowerCase(); }
  function clean(s) { return (s || '').replace(/^\s*\+\s*/, '').replace(/\s+/g, ' ').trim() || 'Layer'; }

  /* ── reading a Photoshop file into a layer tree ── */
  AL.importPsd = function (buf, name) {
    var doc = root.ALPsd.parse(buf), jobs = [];
    function conv(L) {
      if (L.kind === 'group') return { name: L.name, kind: 'group', visible: L.visible, opacity: L.opacity, kids: L.children.map(conv).filter(Boolean) };
      if (!(L.w > 0 && L.h > 0)) return null;
      var id = AL.id('i'), o = { name: L.name, kind: 'layer', visible: L.visible, opacity: L.opacity, img: id, x: L.left, y: L.top };
      jobs.push(root.ALPsd.decode(doc, L).then(function (im) { AL.img.fromRGBA(id, im.w, im.h, im.data, L.name); }));
      return o;
    }
    var kids = doc.tree.map(conv).filter(Boolean);
    return Promise.all(jobs).then(function () { return { name: name || 'Character', w: doc.width, h: doc.height, kids: kids, layers: doc.flat.length }; });
  };

  /* ── names ── */
  function sideOf(n) { var s = ' ' + lc(n).replace(/[_\-.]/g, ' ') + ' '; if (/ (left|l|lft) /.test(s)) return 'L'; if (/ (right|r|rgt) /.test(s)) return 'R'; return null; }
  function roleOf(name, parentRole, inheritedSide) {
    var s = lc(name).replace(/[_\-.]/g, ' '), side = sideOf(name) || inheritedSide;
    if (/pupil|iris/.test(s)) return 'pupil';
    if (/eyeball|eye white|sclera/.test(s)) return 'eyeball';
    if (/blink|eyelid|eye closed|closed eye/.test(s)) return 'blink';
    if (/brow/.test(s)) return side === 'R' ? 'browR' : 'browL';
    if (/\beyes?\b/.test(s)) return side === 'R' ? 'eyeR' : 'eyeL';
    if (parentRole === 'mouth') return null;
    if (/mouth|lips/.test(s)) return 'mouth';
    if (/\b(jaw|chin)\b/.test(s)) return 'jaw';
    if (/nose|snout|muzzle|beak/.test(s)) return 'nose';
    if (/hair|bangs?\b|frill|crest|\bears?\b|horn|antenna|mane|feather|hat\b/.test(s)) return 'hair';
    if (/\bhead\b/.test(s)) return 'head';
    if (/\bface\b|skull/.test(s)) return 'face';
    if (/\barms?\b|\barm /.test(s) || /\barm/.test(s)) return side === 'R' ? 'armR' : 'armL';
    if (/\b(legs?|foot|feet)\b/.test(s)) return side === 'R' ? 'legR' : 'legL';
    if (/\btail\b/.test(s)) return 'tail';
    if (/\b(torso|chest|belly)\b/.test(s)) return 'torso';
    if (/\bbody\b/.test(s)) return 'body';
    return null;
  }
  function viewPos(name) {
    var s = lc(name), x = 0, y = 0, known = false;
    var L = /\bleft\b/.test(s), R = /\bright\b/.test(s);
    if (/profile|side/.test(s)) { x = 1; known = true; }
    else if (/quarter|three|3\/4|34/.test(s)) { x = 0.5; known = true; }
    else if (L || R) { x = 1; known = true; }
    if (/front|centre|center|forward|straight|neutral/.test(s)) { known = true; if (!(L || R)) x = 0; }
    if (/\bup(ward)?s?\b|\btop\b/.test(s)) { y = -1; known = true; }
    if (/\bdown(ward)?s?\b|\bbottom\b/.test(s)) { y = 1; known = true; }
    if (L) x = -x;
    return known ? [x, y] : null;
  }
  var SHAPE_ALIAS = { neutral: 'Neutral', rest: 'Neutral', closed: 'M', aa: 'Aa', ah: 'Aa', a: 'Aa', ee: 'Ee', e: 'Ee', oh: 'Oh', o: 'Oh', 'w-oo': 'W-Oo', woo: 'W-Oo', oo: 'W-Oo', w: 'W-Oo', u: 'W-Oo',
    m: 'M', mbp: 'M', b: 'M', p: 'M', f: 'F', fv: 'F', v: 'F', l: 'L', s: 'S', d: 'D', r: 'R', uh: 'Uh', smile: 'Smile', surprised: 'Surprised' };
  function shapeOf(name) { return SHAPE_ALIAS[lc(clean(name)).replace(/\s+/g, '')] || null; }

  /* ── pixels ── */
  function eachOpaque(n, step, fn) {
    var im = AL.img.get(n.img); if (!im) return; var a = AL.img.alpha(n.img);
    for (var y = 0; y < im.h; y += step) for (var x = 0; x < im.w; x += step) if (a[y * im.w + x] > 60) fn(n.x + x, n.y + y);
  }
  function boxOf(n) { var im = AL.img.get(n.img); return im ? { x0: n.x, y0: n.y, x1: n.x + im.w, y1: n.y + im.h, w: im.w, h: im.h, cx: n.x + im.w / 2, cy: n.y + im.h / 2 } : null; }
  function ringCentre(n, c, r0, r1) {
    var sx = 0, sy = 0, k = 0;
    eachOpaque(n, 1, function (x, y) { var d = hyp(x - c.x, y - c.y); if (d >= r0 && d <= r1) { sx += x; sy += y; k++; } });
    return k >= 4 ? { x: sx / k, y: sy / k } : null;
  }
  function farthest(n, c) { var best = null, bd = -1; eachOpaque(n, 1, function (x, y) { var d = hyp(x - c.x, y - c.y); if (d > bd) { bd = d; best = { x: x, y: y }; } }); return best ? { p: best, d: bd } : null; }
  /** The two ends of a long shape (principal axis). */
  function ends(n) {
    var sx = 0, sy = 0, k = 0, pts = [];
    eachOpaque(n, 2, function (x, y) { sx += x; sy += y; k++; pts.push(x, y); });
    if (k < 3) return null;
    var cx = sx / k, cy = sy / k, xx = 0, xy = 0, yy = 0, i;
    for (i = 0; i < pts.length; i += 2) { var dx = pts[i] - cx, dy = pts[i + 1] - cy; xx += dx * dx; xy += dx * dy; yy += dy * dy; }
    var ang = 0.5 * Math.atan2(2 * xy, xx - yy), ux = Math.cos(ang), uy = Math.sin(ang), lo = 1e9, hi = -1e9;
    for (i = 0; i < pts.length; i += 2) { var t = (pts[i] - cx) * ux + (pts[i + 1] - cy) * uy; if (t < lo) lo = t; if (t > hi) hi = t; }
    return [{ x: cx + ux * lo * 0.92, y: cy + uy * lo * 0.92 }, { x: cx + ux * hi * 0.92, y: cy + uy * hi * 0.92 }];
  }

  /** A limb's two ends, plain end first: a hand, paw or foot is the bigger blob; a shoulder is just the cut end. */
  function plainEndFirst(n) {
    var e = ends(n); if (!e) return null;
    var r = hyp(e[0].x - e[1].x, e[0].y - e[1].y) * 0.22, c0 = 0, c1 = 0;
    eachOpaque(n, 2, function (x, y) { if (hyp(x - e[0].x, y - e[0].y) < r) c0++; if (hyp(x - e[1].x, y - e[1].y) < r) c1++; });
    return c0 <= c1 ? [e[0], e[1]] : [e[1], e[0]];
  }

  /**
   * Pins for a fan of limb drawings. The trick: drawings of one arm at many angles all overlap at the
   * SHOULDER, so the shoulder is simply where most of them are opaque at once. The hand is the far end.
   */
  AL.guessFan = function (ch, set, near) {
    var kids = (set.kids || []).filter(function (k) { return k.type === 'art' && AL.img.get(k.img); });
    if (!kids.length) return;
    var b = AL.boundsOf(set), bw = Math.ceil(b.x1 - b.x0), bh = Math.ceil(b.y1 - b.y0), cover = new Uint8Array(bw * bh), mx = 0, shoulder = null;
    kids.forEach(function (k) { eachOpaque(k, 1, function (x, y) { var i = (y - b.y0 | 0) * bw + (x - b.x0 | 0); if (++cover[i] > mx) mx = cover[i]; }); });
    if (kids.length >= 3 && mx >= 3) {
      var thr = Math.max(2, Math.ceil(mx * 0.72)), sx = 0, sy = 0, c = 0;
      for (var i = 0; i < cover.length; i++) if (cover[i] >= thr) { sx += i % bw; sy += (i / bw) | 0; c++; }
      if (c) shoulder = { x: b.x0 + sx / c, y: b.y0 + sy / c };
    }
    var lens = [];
    kids.forEach(function (k) {
      var a = shoulder;
      if (!a) { var e = near ? ends(k) : plainEndFirst(k); if (!e) return; a = near ? (hyp(e[0].x - near.x, e[0].y - near.y) <= hyp(e[1].x - near.x, e[1].y - near.y) ? e[0] : e[1]) : e[0]; }
      var f = farthest(k, a); if (!f) return;
      var len = f.d, wrist = ringCentre(k, a, len * 0.74, len * 0.92) || { x: a.x + (f.p.x - a.x) * 0.85, y: a.y + (f.p.y - a.y) * 0.85 };
      var wl = hyp(wrist.x - a.x, wrist.y - a.y);
      var elbow = ringCentre(k, a, wl * 0.44, wl * 0.56) || { x: (a.x + wrist.x) / 2, y: (a.y + wrist.y) / 2 };
      var keep = k.limb || {};
      k.limb = { a: { x: a.x, y: a.y }, b: elbow, c: wrist, flip: !!keep.flip, bend: keep.bend, guessed: true };
      lens.push(wl);
    });
    /* a drawing far shorter than its sisters points at the camera: keep its elbow simple */
    var med = lens.slice().sort(function (p, q) { return p - q; })[lens.length >> 1] || 0;
    kids.forEach(function (k) { if (!k.limb) return; var l = k.limb, wl = hyp(l.c.x - l.a.x, l.c.y - l.a.y); if (wl < med * 0.5) l.b = { x: (l.a.x + l.c.x) / 2, y: (l.a.y + l.c.y) / 2 }; });
    var rest = null; kids.forEach(function (k) { if (k.limb && (!rest || k.limb.c.y > rest.limb.c.y)) rest = k; });
    set.pick = set.pick || {}; if (rest && (!set.pick.rest || !AL.node(ch, set.pick.rest))) set.pick.rest = rest.id;
  };

  AL.guessMouth = function (ch, art, scope) {
    var b = boxOf(art); if (!b) return;
    delete art.hinge; delete art.squash;
    if (b.w / b.h >= 1.5) { art.squash = { rest: 'open', min: 0.14 }; art.pivot = { x: b.cx, y: b.y0 + b.h * 0.15 }; return; }
    var nose = scope ? AL.firstRole(scope, 'nose') : null, nb = nose ? AL.boundsOf(nose) : null, face = scope ? AL.boundsOf(scope) : null;
    var towards = nb ? (nb.x0 + nb.x1) / 2 : face ? (face.x0 + face.x1) / 2 - (b.cx - (face.x0 + face.x1) / 2) : b.x0;
    var hingeRight = towards < b.cx;                    // the lips point at the nose; the hinge is at the other corner
    art.pivot = { x: hingeRight ? b.x0 + b.w * 0.86 : b.x0 + b.w * 0.14, y: b.y0 + b.h * 0.3 };
    art.hinge = { rest: 'open', angle: hingeRight ? 30 : -30 };
  };
  AL.guessTail = function (ch, art, near) {
    var e = ends(art); if (!e) return;
    var d0 = near ? hyp(e[0].x - near.x, e[0].y - near.y) : e[0].y, d1 = near ? hyp(e[1].x - near.x, e[1].y - near.y) : e[1].y;
    var rootP = d0 <= d1 ? e[0] : e[1], tip = d0 <= d1 ? e[1] : e[0], keep = art.dangle || {};
    art.pivot = rootP; art.dangle = { tip: tip, stiff: keep.stiff == null ? 70 : keep.stiff, damp: keep.damp == null ? 7 : keep.damp, swing: keep.swing == null ? 1 : keep.swing, wag: keep.wag == null ? 0.05 : keep.wag, on: keep.on };
  };

  function trigger(ch, name) {
    var key = lc(name), t = ch.triggers.filter(function (q) { return lc(q.name) === key; })[0];
    if (!t) { t = { id: AL.id('t'), name: name }; ch.triggers.push(t); }
    return t.id;
  }

  /* ── the whole pass ── */
  AL.autoRig = function (doc) {
    var ch = AL.newCharacter(doc.name, doc.w, doc.h);
    function conv(l) {
      var n = { id: AL.id('n'), name: clean(l.name), type: l.kind === 'group' ? 'group' : 'art' };
      if (l.visible === false) n.hidden = true;
      if (l.opacity != null && l.opacity < 0.999) n.opacity = l.opacity;
      if (/^\s*\+/.test(l.name)) n.free = true;
      if (n.type === 'group') n.kids = (l.kids || []).map(conv).filter(Boolean); else { if (!l.img) return null; n.img = l.img; n.x = l.x; n.y = l.y; }
      return n;
    }
    var kids = (doc.kids || []).map(conv).filter(Boolean);
    var shown = kids.filter(function (k) { return !k.hidden; });
    if (shown.length === 1 && shown[0].type === 'group' && (!doc.name || doc.name === 'Character')) ch.name = shown[0].name;
    ch.root.name = ch.name; ch.root.kids = kids; AL.reindex(ch);

    /* 1. what is everything? */
    (function tag(n, parentRole, side) {
      if (n !== ch.root) { var r = roleOf(n.name, parentRole, side); if (r) n.role = r; }
      var s2 = sideOf(n.name) || side, pr = n.role || parentRole;
      if (n.kids) n.kids.forEach(function (k) { tag(k, n.role === 'mouth' ? 'mouth' : (/^eye/.test(n.role || '') ? n.role : (n.role || parentRole)), /^eye|^brow|^arm|^leg/.test(n.role || '') ? (/R$/.test(n.role) ? 'R' : 'L') : s2); });
      void pr;
    })(ch.root, null, null);

    /* 2. the torso: named, or the biggest unnamed drawing in the body folder */
    var torso = AL.firstRole(ch.root, 'torso'), bodyG = AL.firstRole(ch.root, 'body');
    if (!torso && bodyG) {
      if (bodyG.type === 'art') { bodyG.role = 'torso'; torso = bodyG; }
      else { var big = null, ba = 0; bodyG.kids.forEach(function (k) { if (k.type !== 'art' || k.role) return; var im = AL.img.get(k.img), a = im ? im.w * im.h : 0; if (a > ba) { ba = a; big = k; } }); if (big) { big.role = 'torso'; torso = big; } }
    }
    var tb = torso ? boxOf(torso) : null, tc = tb ? { x: tb.cx, y: tb.cy } : null;
    if (torso) {
      torso.breathe = { cx: tb.cx, cy: tb.y0 + tb.h * 0.42, r: Math.min(tb.w, tb.h) * 0.5, amt: 1 };
      torso.pivot = { x: tb.cx, y: tb.y1 - tb.h * 0.08 };
      torso.links = [{ sig: 'sway', prop: 'rot', amt: 5 }, { sig: 'bounce', prop: 'y', amt: 4 }];
      ch.settings.axisX = tb.cx;
    }

    /* 3. arms: every folder for one arm becomes ONE fan; drawings from a folder elsewhere keep their place in the stack */
    ['armL', 'armR'].forEach(function (role) {
      var nodes = AL.byRole(ch.root, role).filter(function (n) { return !AL.ancestorWith(ch, AL.parentOf(ch, n.id) ? AL.parentOf(ch, n.id).id : '', function (a) { return a.role === role; }); });
      if (!nodes.length) return;
      var main = nodes[0], set;
      if (main.type === 'group') { set = main; set.type = 'set'; }
      else { var par = AL.parentOf(ch, main.id), at = par.kids.indexOf(main); set = { id: AL.id('n'), name: AL.ROLES[role].label, type: 'set', role: role, kids: [main] }; delete main.role; par.kids[at] = set; }
      delete set.hidden;
      for (var i = 1; i < nodes.length; i++) {
        var g = nodes[i], gp = AL.parentOf(ch, g.id), gi = gp.kids.indexOf(g), above = gi > 0 ? gp.kids[gi - 1] : null;
        var moved = g.type === 'group' ? g.kids : [g];
        moved.forEach(function (k) { if (above) k.zUnder = above.id; delete k.role; set.kids.push(k); });
        gp.kids.splice(gi, 1);
      }
      set.kids.forEach(function (k) { delete k.hidden; });
      set.pick = { by: 'reach', hand: role === 'armR' ? 'R' : 'L', fade: 110 };
      if (torso) set.attach = torso.id;
      set.links = [{ sig: 'breath', prop: 'y', amt: -0.8 }];
      AL.reindex(ch);
      AL.guessFan(ch, set, tc);
    });

    /* 4. the head: angle folders become a turn set; a face drawn once stays one folder */
    var head = AL.firstRole(ch.root, 'head');
    if (head && head.type === 'group') {
      var views = head.kids.filter(function (k) { return k.type === 'group' && viewPos(k.name); });
      if (views.length >= 2) {
        head.type = 'set'; head.kids = head.kids.filter(function (k) { return k.type === 'group'; });
        var spare = 0;
        head.kids.forEach(function (k) { k.at = viewPos(k.name) || [Math.cos(spare) * 0.8, Math.sin(spare++) * 0.8]; delete k.hidden; });
        var front = null, fd = 9; head.kids.forEach(function (k) { var d = hyp(k.at[0], k.at[1]); if (d < fd) { fd = d; front = k; } });
        head.pick = { by: 'turn', depth: 12, fade: 110, rest: front.id };
        var fb = AL.boundsOf(front); if (fb) head.axisX = (fb.x0 + fb.x1) / 2;
        var hasL = head.kids.some(function (k) { return k.at[0] < -0.1; }), hasR = head.kids.some(function (k) { return k.at[0] > 0.1; });
        if (hasL !== hasR) head.kids.slice().forEach(function (k) { if (Math.abs(k.at[0]) > 0.1) head.kids.push({ id: AL.id('n'), name: k.name.replace(/left/i, 'Right').replace(/right/i, 'Left') + ' (mirrored)', type: 'mirror', of: k.id, at: [-k.at[0], k.at[1]], auto: true }); });
      }
    }
    if (head) {
      var hb = AL.boundsOf(head);
      if (hb) head.pivot = tb ? { x: Math.max(hb.x0, Math.min(hb.x1, tb.cx)), y: Math.min(hb.y1, tb.y0 + tb.h * 0.12) } : { x: (hb.x0 + hb.x1) / 2, y: hb.y1 - (hb.y1 - hb.y0) * 0.1 };
      head.links = [{ sig: 'roll', prop: 'rot', amt: 16 }, { sig: 'breath', prop: 'y', amt: -1.4 }, { sig: 'turnX', prop: 'x', amt: 3 }, { sig: 'turnY', prop: 'y', amt: 2.5 }];
      if (torso) head.attach = torso.id;
    }

    /* 5. faces: eyes, brows, mouths, jaws, in every angle */
    var scopes = head && head.type === 'set' ? head.kids.filter(function (k) { return k.type === 'group'; }) : [head || ch.root];
    scopes.forEach(function (scope) {
      AL.walk(scope, function (n) {
        var r = n.role;
        if (r === 'eyeL' || r === 'eyeR') {
          var side = r === 'eyeR' ? 'R' : 'L';
          if (n.type === 'group') {
            var hasBlink = n.kids.some(function (k) { return k.role === 'blink'; });
            n.kids.forEach(function (k) {
              if (k.role === 'blink') { k.blink = 'closed'; k.side = side; delete k.hidden; }
              else if (!k.role || k.role === 'pupil' || k.role === 'eyeball') { if (hasBlink) { k.blink = 'open'; k.side = side; } }
              if (k.role === 'pupil' && k.type === 'art') { var im = AL.img.get(k.img); k.look = { rx: Math.max(1.5, im.w * 0.12), ry: Math.max(1, im.h * 0.08) }; }
            });
          }
          if (n.type === 'group' && n.kids.length && n.kids.every(function (k) { return k.role === 'blink' || k.hidden; })) n.hidden = true;   // this eye is not drawn at this angle
          n.depth = 0.6;
        } else if (r === 'browL' || r === 'browR') {
          var bb = AL.boundsOf(n); n.links = [{ sig: r, prop: 'y', amt: -Math.max(3, (bb ? bb.y1 - bb.y0 : 20) * 0.14) }]; n.depth = 0.6;
        } else if (r === 'nose') { n.depth = 1; }
        else if (r === 'hair') { n.depth = /back|rear|behind/i.test(n.name) ? -1 : -0.7; }
        else if (r === 'jaw') { n.links = [{ sig: 'mouth', prop: 'y', amt: 3, bias: -1 }]; n.depth = 0.8; }
        else if (r === 'mouth') {
          n.depth = 0.8;
          if (n.type === 'group') {
            n.type = 'set';
            var allHidden = n.kids.every(function (k) { return k.hidden; });
            var named = n.kids.filter(function (k) { return shapeOf(k.name); });
            if (named.length >= 3) { n.pick = { by: 'viseme' }; n.kids.forEach(function (k) { var sh = shapeOf(k.name); if (sh) k.shape = sh; else k.trig = trigger(ch, k.name); if (sh === 'Smile' || sh === 'Surprised') k.trig = trigger(ch, sh); delete k.hidden; }); var nt = n.kids.filter(function (k) { return k.shape === 'Neutral'; })[0] || n.kids[0]; n.pick.rest = nt.id; }
            else {
              var rest = n.kids.filter(function (k) { return !k.hidden && k.type === 'art'; })[0] || n.kids.filter(function (k) { return /mouth|open|talk/i.test(k.name); })[0] || n.kids[0];
              n.pick = { by: 'default', rest: rest.id };
              n.kids.forEach(function (k) { if (k !== rest) k.trig = trigger(ch, k.name); delete k.hidden; });
              if (rest.type === 'art') AL.guessMouth(ch, rest, scope);
            }
            if (allHidden) n.hidden = true;
          } else AL.guessMouth(ch, n, scope);
          return false;
        } else if (!r && /back|behind|rear/i.test(n.name) && n.type === 'art') n.depth = -0.5;
      });
    });

    /* 6. tails swing; anything else on the body rides the torso */
    AL.byRole(ch.root, 'tail').forEach(function (t) { if (t.type === 'art') { AL.guessTail(ch, t, tc); if (torso) t.attach = torso.id; } });
    if (torso) {
      var tail = AL.firstRole(ch.root, 'tail'), tbx = tail ? AL.boundsOf(tail) : null;
      if (tbx) ch.settings.facing = (tbx.x0 + tbx.x1) / 2 > tb.cx ? 'left' : 'right';
    }
    AL.reindex(ch);
    return ch;
  };

  /** The owner says "this layer is the left arm": give it that part's default rig. Used by every door and by the role picker. */
  AL.applyRole = function (ch, n, role) {
    var torso = AL.firstRole(ch.root, 'torso'); if (torso === n) torso = null;
    var tb = torso ? boxOf(torso) : null, tc = tb ? { x: tb.cx, y: tb.cy } : null;
    if (role === 'prop') { delete n.role; AL.reindex(ch); return; }
    n.role = role;
    if (role === 'armL' || role === 'armR') {
      var set = n;
      if (n.type === 'art') { var par = AL.parentOf(ch, n.id), at = par.kids.indexOf(n); set = { id: AL.id('n'), name: AL.ROLES[role].label, type: 'set', role: role, kids: [n] }; delete n.role; par.kids[at] = set; }
      else set.type = 'set';
      set.kids.forEach(function (k) { delete k.hidden; });
      set.pick = { by: 'reach', hand: role === 'armR' ? 'R' : 'L', fade: set.pick && set.pick.fade != null ? set.pick.fade : 110, rest: set.pick && set.pick.rest };
      if (torso) set.attach = torso.id;
      if (!set.links || !set.links.length) set.links = [{ sig: 'breath', prop: 'y', amt: -0.8 }];
      AL.reindex(ch); AL.guessFan(ch, set, tc);
    } else if (role === 'tail') {
      var art = n.type === 'art' ? n : AL.find(n, function (k) { return k.type === 'art'; });
      if (art) { AL.guessTail(ch, art, tc); if (torso) art.attach = torso.id; }
    } else if (role === 'torso') {
      if (n.type === 'art') {
        var b = boxOf(n);
        if (!n.mesh) n.breathe = { cx: b.cx, cy: b.y0 + b.h * 0.42, r: Math.min(b.w, b.h) * 0.5, amt: 1 };
        n.pivot = { x: b.cx, y: b.y1 - b.h * 0.08 }; n.links = [{ sig: 'sway', prop: 'rot', amt: 5 }, { sig: 'bounce', prop: 'y', amt: 4 }]; ch.settings.axisX = b.cx;
        ['head', 'armL', 'armR'].forEach(function (r) { var q = AL.firstRole(ch.root, r); if (q && !q.attach) q.attach = n.id; });
        AL.byRole(ch.root, 'tail').forEach(function (q) { if (q.type === 'art' && !q.attach) q.attach = n.id; });
      }
    } else if (role === 'head') {
      var hb = AL.boundsOf(n);
      if (hb) n.pivot = tb ? { x: Math.max(hb.x0, Math.min(hb.x1, tb.cx)), y: Math.min(hb.y1, tb.y0 + tb.h * 0.12) } : { x: (hb.x0 + hb.x1) / 2, y: hb.y1 - (hb.y1 - hb.y0) * 0.1 };
      if (!n.links || !n.links.length) n.links = [{ sig: 'roll', prop: 'rot', amt: 16 }, { sig: 'breath', prop: 'y', amt: -1.4 }, { sig: 'turnX', prop: 'x', amt: 3 }, { sig: 'turnY', prop: 'y', amt: 2.5 }];
      if (torso) n.attach = torso.id;
      if (n.type === 'set' && hb) n.axisX = (hb.x0 + hb.x1) / 2;
    } else if (role === 'mouth') { if (n.type === 'art') AL.guessMouth(ch, n, AL.parentOf(ch, n.id)); n.depth = 0.8; }
    else if (role === 'pupil') { if (n.type === 'art') { var im = AL.img.get(n.img); n.look = { rx: Math.max(1.5, im.w * 0.12), ry: Math.max(1, im.h * 0.08) }; } }
    else if (role === 'nose') n.depth = 1;
    else if (role === 'hair') n.depth = -0.7;
    else if (role === 'jaw') { n.links = [{ sig: 'mouth', prop: 'y', amt: 3, bias: -1 }]; n.depth = 0.8; }
    else if (role === 'browL' || role === 'browR') { var bb = AL.boundsOf(n); n.links = [{ sig: role, prop: 'y', amt: -Math.max(3, (bb ? bb.y1 - bb.y0 : 20) * 0.14) }]; n.depth = 0.6; }
    AL.reindex(ch);
  };

  /** A plain-words summary of what the automatic rig found, zone by zone. */
  AL.describe = function (ch) {
    var out = {}, head = AL.firstRole(ch.root, 'head');
    if (head) { var nv = head.type === 'set' ? head.kids.length : 1; out.head = nv > 1 ? nv + ' angles' : 'one drawing'; }
    ['armL', 'armR'].forEach(function (r) { var a = AL.firstRole(ch.root, r); if (a) { var k = (a.kids || []).filter(function (q) { return q.limb; }).length; out[r] = k > 1 ? k + ' drawings, swaps and bends' : 'bends to reach'; } });
    var t = AL.firstRole(ch.root, 'torso') || AL.firstRole(ch.root, 'body'); if (t) out.body = t.breathe ? 'breathes' : 'in place';
    ['legL', 'legR'].forEach(function (r) { if (AL.firstRole(ch.root, r)) out[r] = 'in place'; });
    var tl = AL.firstRole(ch.root, 'tail'); if (tl) out.tail = tl.dangle ? 'swings' : 'in place';
    return out;
  };
  AL._px = { plainEndFirst: plainEndFirst, ends: ends, boxOf: boxOf, farthest: farthest, ringCentre: ringCentre, eachOpaque: eachOpaque, viewPos: viewPos, shapeOf: shapeOf, roleOf: roleOf, trigger: trigger };
})(typeof self !== 'undefined' ? self : this);
