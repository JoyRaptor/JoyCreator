/* AvatarLab: a small Photoshop (.psd / .psb) reader.
 *
 * Reads the layer tree (groups, names, visibility, opacity, blend, clipping, bounds) and
 * decodes any layer's pixels to straight RGBA on demand. 8-bit RGB / grey files; raw, RLE
 * and ZIP channel data; user masks. Nothing here touches the DOM, so the same logic ports
 * to Java/Kotlin line for line (the phone needs its own PSD door: phone-first).
 *
 *   var doc = ALPsd.parse(arrayBuffer);          // { width, height, tree: [node...] }
 *   node = { name, kind:'group'|'layer', visible, opacity, blend, clip, left, top, w, h, children }
 *   ALPsd.decode(doc, node).then(function (img) { img.data  // Uint8ClampedArray RGBA, img.w, img.h });
 */
(function (root) {
  'use strict';

  function Reader(buf) { this.dv = new DataView(buf); this.u8 = new Uint8Array(buf); this.p = 0; }
  Reader.prototype.u8_ = function () { return this.dv.getUint8(this.p++); };
  Reader.prototype.u16 = function () { var v = this.dv.getUint16(this.p); this.p += 2; return v; };
  Reader.prototype.i16 = function () { var v = this.dv.getInt16(this.p); this.p += 2; return v; };
  Reader.prototype.u32 = function () { var v = this.dv.getUint32(this.p); this.p += 4; return v; };
  Reader.prototype.i32 = function () { var v = this.dv.getInt32(this.p); this.p += 4; return v; };
  Reader.prototype.u64 = function () { var hi = this.u32(), lo = this.u32(); return hi * 4294967296 + lo; };
  Reader.prototype.str = function (n) {
    var s = ''; for (var i = 0; i < n; i++) s += String.fromCharCode(this.u8[this.p + i]); this.p += n; return s;
  };

  var BIG_KEYS = { LMsk: 1, Lr16: 1, Lr32: 1, Layr: 1, Mt16: 1, Mt32: 1, Mtrn: 1, Alph: 1, FMsk: 1, lnk2: 1, FEid: 1, FXid: 1, PxSD: 1 };

  function parse(buf) {
    var r = new Reader(buf);
    if (r.str(4) !== '8BPS') throw new Error('This is not a Photoshop file.');
    var version = r.u16(); var big = version === 2;
    r.p += 6;
    var doc = { channels: r.u16(), height: r.u32(), width: r.u32(), depth: r.u16(), mode: r.u16(), big: big, tree: [], flat: [], buf: buf };
    if (doc.depth !== 8) throw new Error('This file is ' + doc.depth + '-bit. Save it as 8-bit (Image > Mode > 8 Bits/Channel) and try again.');
    if (doc.mode !== 3 && doc.mode !== 1) throw new Error('This file is not RGB. Change it to RGB Color (Image > Mode) and try again.');
    var cmLen = r.u32(); r.p += cmLen;          // colour mode data
    var resLen = r.u32(); r.p += resLen;      // image resources
    var lmLen = big ? r.u64() : r.u32();
    var lmEnd = r.p + lmLen;
    if (lmLen > 0) {
      var liLen = big ? r.u64() : r.u32();
      if (liLen > 0) {
        readLayerInfo(r, doc);
      } else {
        // 16/32-bit style: layers hide in a tagged block after the global mask. Kept for odd 8-bit writers.
        var gm = r.u32(); r.p += gm;
        while (r.p + 12 <= lmEnd) {
          var sig = r.str(4), key = r.str(4);
          var len = (sig === '8B64' || (big && BIG_KEYS[key])) ? r.u64() : r.u32();
          var end = r.p + len;
          if (key === 'Layr' || key === 'Lr16') { readLayerInfo(r, doc); }
          r.p = end + (len % 4 ? 4 - (len % 4) : 0);
        }
      }
    }
    doc.tree = buildTree(doc.flat);
    return doc;
  }

  function readLayerInfo(r, doc) {
    var count = Math.abs(r.i16());
    var recs = [];
    for (var i = 0; i < count; i++) {
      var L = { kind: 'layer', children: null };
      L.top = r.i32(); L.left = r.i32(); L.bottom = r.i32(); L.right = r.i32();
      L.w = Math.max(0, L.right - L.left); L.h = Math.max(0, L.bottom - L.top);
      var nch = r.u16(); L.ch = [];
      for (var c = 0; c < nch; c++) L.ch.push({ id: r.i16(), len: doc.big ? r.u64() : r.u32(), off: 0 });
      r.p += 4;                               // '8BIM'
      L.blend = r.str(4).trim();
      L.opacity = r.u8_() / 255;
      L.clip = r.u8_() === 1;
      var flags = r.u8_(); r.p += 1;
      L.visible = (flags & 2) === 0;
      var extraLen = r.u32(); var extraEnd = r.p + extraLen;
      var maskLen = r.u32(); var maskEnd = r.p + maskLen;
      if (maskLen >= 18) {
        var mt = r.i32(), ml = r.i32(), mb = r.i32(), mr = r.i32();
        var mdef = r.u8_(), mflags = r.u8_();
        L.mask = { top: mt, left: ml, w: Math.max(0, mr - ml), h: Math.max(0, mb - mt), def: mdef, off: (mflags & 2) !== 0 };
      }
      r.p = maskEnd;
      var brLen = r.u32(); r.p += brLen;        // blending ranges
      var nlen = r.u8_(); L.name = r.str(nlen);
      var pad = (nlen + 1) % 4; if (pad) r.p += 4 - pad;
      L.section = 0;
      while (r.p + 12 <= extraEnd) {
        var sig = r.str(4), key = r.str(4);
        var len = (sig === '8B64' || (doc.big && BIG_KEYS[key])) ? r.u64() : r.u32();
        var end = r.p + len;
        if (key === 'luni') {
          var n = r.u32(), s = '';
          for (var k = 0; k < n; k++) { var cc = r.u16(); if (cc) s += String.fromCharCode(cc); }
          L.name = s;
        } else if (key === 'lsct' || key === 'lsdk') {
          L.section = r.u32();
          if (len >= 12) { r.p += 4; var bm = r.str(4).trim(); if (bm) L.blend = bm; }
        }
        r.p = end + (len & 1);
      }
      r.p = extraEnd;
      recs.push(L);
    }
    for (var j = 0; j < recs.length; j++) {
      for (var c2 = 0; c2 < recs[j].ch.length; c2++) { recs[j].ch[c2].off = r.p; r.p += recs[j].ch[c2].len; }
    }
    doc.flat = recs;
  }

  /** File order is bottom-to-top with a hidden "end of group" marker under each group. Returns top-to-bottom, as Photoshop's panel shows it. */
  function buildTree(flat) {
    var rootKids = []; var stack = [rootKids]; var path = [];
    for (var i = flat.length - 1; i >= 0; i--) {
      var L = flat[i];
      if (L.section === 1 || L.section === 2) {
        L.kind = 'group'; L.children = []; L.path = path.concat([L.name]).join('/');
        stack[stack.length - 1].push(L); stack.push(L.children); path.push(L.name);
      } else if (L.section === 3) {
        if (stack.length > 1) { stack.pop(); path.pop(); }
      } else {
        L.path = path.concat([L.name]).join('/');
        stack[stack.length - 1].push(L);
      }
    }
    return rootKids;
  }

  function unpackBits(src, p, end, out, o, n) {
    var stop = o + n;
    while (o < stop && p < end) {
      var c = src[p++];
      if (c < 128) { c += 1; for (var i = 0; i < c && o < stop; i++) out[o++] = src[p++]; }
      else if (c > 128) { c = 257 - c; var v = src[p++]; for (var j = 0; j < c && o < stop; j++) out[o++] = v; }
    }
    return p;
  }

  function inflate(bytes) {
    if (typeof DecompressionStream !== 'undefined') {
      var ds = new DecompressionStream('deflate');
      var w = ds.writable.getWriter(); w.write(bytes); w.close();
      return new Response(ds.readable).arrayBuffer().then(function (ab) { return new Uint8Array(ab); });
    }
    return Promise.reject(new Error('This file uses ZIP layers, which this browser cannot open.'));
  }

  /** One channel plane (w*h bytes). Resolves synchronously for raw and RLE. */
  function readPlane(doc, ch, w, h) {
    var u8 = new Uint8Array(doc.buf); var dv = new DataView(doc.buf);
    var out = new Uint8Array(w * h);
    if (!w || !h || ch.len < 2) return Promise.resolve(out);
    var p = ch.off; var comp = dv.getUint16(p); p += 2; var end = ch.off + ch.len;
    if (comp === 0) { out.set(u8.subarray(p, Math.min(end, p + w * h))); return Promise.resolve(out); }
    if (comp === 1) {
      p += h * (doc.big ? 4 : 2);
      for (var y = 0; y < h; y++) p = unpackBits(u8, p, end, out, y * w, w);
      return Promise.resolve(out);
    }
    return inflate(u8.slice(p, end)).then(function (raw) {
      out.set(raw.subarray(0, w * h));
      if (comp === 3) for (var y2 = 0; y2 < h; y2++) for (var x = 1, o = y2 * w + 1; x < w; x++, o++) out[o] = (out[o] + out[o - 1]) & 255;
      return out;
    });
  }

  /** Straight (un-premultiplied) RGBA for one layer; layer opacity is NOT baked in. */
  function decode(doc, L) {
    var w = L.w, h = L.h; var byId = {};
    L.ch.forEach(function (c) { byId[c.id] = c; });
    var jobs = [0, 1, 2, -1].map(function (id) { return byId[id] ? readPlane(doc, byId[id], w, h) : Promise.resolve(null); });
    var mjob = (L.mask && !L.mask.off && byId[-2]) ? readPlane(doc, byId[-2], L.mask.w, L.mask.h) : Promise.resolve(null);
    return Promise.all(jobs.concat([mjob])).then(function (pl) {
      var R = pl[0], G = pl[1] || pl[0], B = pl[2] || pl[0], A = pl[3], M = pl[4];
      var data = new Uint8ClampedArray(w * h * 4);
      for (var i = 0, o = 0, n = w * h; i < n; i++, o += 4) {
        data[o] = R ? R[i] : 0; data[o + 1] = G ? G[i] : 0; data[o + 2] = B ? B[i] : 0; data[o + 3] = A ? A[i] : 255;
      }
      if (M) {
        var m = L.mask;
        for (var y = 0; y < h; y++) for (var x = 0; x < w; x++) {
          var mx = x + L.left - m.left, my = y + L.top - m.top;
          var mv = (mx >= 0 && my >= 0 && mx < m.w && my < m.h) ? M[my * m.w + mx] : m.def;
          if (mv !== 255) { var k = (y * w + x) * 4 + 3; data[k] = (data[k] * mv / 255) | 0; }
        }
      }
      return { w: w, h: h, data: data };
    });
  }

  /** Every leaf under a node, top-first, with the visibility and opacity it inherits. */
  function leaves(node, out, vis, op) {
    out = out || []; vis = vis === undefined ? true : vis; op = op === undefined ? 1 : op;
    var v = vis && node.visible, o = op * node.opacity;
    if (node.kind === 'group') node.children.forEach(function (c) { leaves(c, out, v, o); });
    else if (node.w > 0 && node.h > 0) out.push({ layer: node, visible: v, opacity: o });
    return out;
  }

  var api = { parse: parse, decode: decode, leaves: leaves };
  root.ALPsd = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof self !== 'undefined' ? self : globalThis);
