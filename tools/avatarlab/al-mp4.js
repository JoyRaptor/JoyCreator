/* AvatarLab: THE VIDEO FILE. Pictures and sound, already compressed by the browser (al-video.js), put into one ordinary MP4 that
 * every player opens: H.264 pictures, AAC sound, the index at the front (so it starts playing at once and seeks).
 *
 * Nothing from outside the page: an MP4 is a nest of labelled boxes, and these are the few a plain one needs.
 *   ftyp                     what kind of file this is
 *   moov  mvhd               the whole film: its length
 *         trak (pictures)    tkhd, mdia (mdhd, hdlr, minf (vmhd, dinf, stbl (stsd avc1+avcC, stts, stss, stsc, stsz, stco)))
 *         trak (sound)       the same, with smhd and mp4a+esds, and an edit list that ends the sound exactly where the take ends
 *   mdat                     the pictures, then the sound
 *
 * Phone: not ported. Video Studio's ExportManager writes its MP4 through Media3 Transformer.
 */
(function (root) {
  'use strict';
  var AL = root.AL, M = AL.mp4 = {};
  function cat(parts) { var n = 0, i; for (i = 0; i < parts.length; i++) n += parts[i].length; var o = new Uint8Array(n), at = 0; for (i = 0; i < parts.length; i++) { o.set(parts[i], at); at += parts[i].length; } return o; }
  function u8(v) { return new Uint8Array([v & 255]); }
  function u16(v) { return new Uint8Array([(v >>> 8) & 255, v & 255]); }
  function u24(v) { return new Uint8Array([(v >>> 16) & 255, (v >>> 8) & 255, v & 255]); }
  function u32(v) { v = Math.max(0, Math.round(v)) >>> 0; return new Uint8Array([(v >>> 24) & 255, (v >>> 16) & 255, (v >>> 8) & 255, v & 255]); }
  function str(s) { var o = new Uint8Array(s.length); for (var i = 0; i < s.length; i++) o[i] = s.charCodeAt(i) & 255; return o; }
  function zero(n) { return new Uint8Array(n); }
  function box(type, parts) { var body = cat(parts || []); return cat([u32(body.length + 8), str(type), body]); }
  function full(type, ver, flags, parts) { return box(type, [u8(ver), u24(flags)].concat(parts || [])); }
  var MATRIX = cat([u32(0x00010000), u32(0), u32(0), u32(0), u32(0x00010000), u32(0), u32(0), u32(0), u32(0x40000000)]);
  function list(vals) { return vals.map(u32); }
  /* an MPEG-4 descriptor, its length written in four bytes (as most writers do) */
  function desc(tag, parts) { var b = cat(parts), n = b.length; return cat([u8(tag), new Uint8Array([0x80 | ((n >>> 21) & 127), 0x80 | ((n >>> 14) & 127), 0x80 | ((n >>> 7) & 127), n & 127]), b]); }

  function hdlr(kind, name) { return full('hdlr', 0, 0, [u32(0), str(kind), zero(12), str(name), zero(1)]); }
  function dinf() { return box('dinf', [full('dref', 0, 0, [u32(1), full('url ', 0, 1)])]); }
  function mdhd(scale, dur) { return full('mdhd', 0, 0, [u32(0), u32(0), u32(scale), u32(dur), u16(0x55c4), u16(0)]); }      // (language: und)
  function tkhd(id, dur, w, h, vol) { return full('tkhd', 0, 3, [u32(0), u32(0), u32(id), u32(0), u32(dur), zero(8), u16(0), u16(0), u16(vol ? 0x0100 : 0), u16(0), MATRIX, u32(w * 65536), u32(h * 65536)]); }
  function stts(durs) { var runs = []; durs.forEach(function (d) { var r = runs[runs.length - 1]; if (r && r[1] === d) r[0]++; else runs.push([1, d]); }); return full('stts', 0, 0, [u32(runs.length)].concat([].concat.apply([], runs.map(list)))); }
  function tables(samples, durs, off) { return [stts(durs), full('stsc', 0, 0, [u32(1), u32(1), u32(samples.length), u32(1)]), full('stsz', 0, 0, [u32(0), u32(samples.length)].concat(samples.map(function (s) { return u32(s.data.length); }))), full('stco', 0, 0, [u32(1), u32(off)])]; }

  function videoTrak(v, off, movieDur) {
    var avc1 = box('avc1', [zero(6), u16(1), u16(0), u16(0), zero(12), u16(v.w), u16(v.h), u32(0x00480000), u32(0x00480000), u32(0), u16(1), zero(32), u16(0x18), u16(0xffff), box('avcC', [v.avcC])]);
    var keys = []; v.samples.forEach(function (s, i) { if (s.key) keys.push(i + 1); });
    var t = tables(v.samples, v.samples.map(function () { return v.frameDur; }), off);      // stts, stsc, stsz, stco
    var stbl = box('stbl', [full('stsd', 0, 0, [u32(1), avc1]), t[0], full('stss', 0, 0, [u32(keys.length)].concat(keys.map(u32))), t[1], t[2], t[3]]);
    var minf = box('minf', [full('vmhd', 0, 1, [zero(8)]), dinf(), stbl]);
    return box('trak', [tkhd(1, movieDur, v.w, v.h, false), box('mdia', [mdhd(v.timescale, v.samples.length * v.frameDur), hdlr('vide', 'VideoHandler'), minf])]);
  }
  function audioTrak(a, off, playMs) {
    var dcd = desc(4, [u8(0x40), u8(0x15), u24(0), u32(a.bitrate || 128000), u32(a.bitrate || 128000), desc(5, [a.asc])]);
    var esds = full('esds', 0, 0, [desc(3, [u16(2), u8(0), dcd, desc(6, [u8(2)])])]);
    var mp4a = box('mp4a', [zero(6), u16(1), zero(8), u16(a.channels), u16(16), u16(0), u16(0), u32(a.rate * 65536), esds]);
    var durs = a.samples.map(function (s) { return s.dur; }), total = durs.reduce(function (x, y) { return x + y; }, 0);
    var stbl = box('stbl', [full('stsd', 0, 0, [u32(1), mp4a])].concat(tables(a.samples, durs, off)));
    var edts = box('edts', [full('elst', 0, 0, [u32(1), u32(playMs), u32(a.skip || 0), u16(1), u16(0)])]);      // (play exactly the take's length, from the first sample)
    return box('trak', [tkhd(2, playMs, 0, 0, true), edts, box('mdia', [mdhd(a.rate, total), hdlr('soun', 'SoundHandler'), box('minf', [full('smhd', 0, 0, [u16(0), u16(0)]), dinf(), stbl])])]);
  }
  /**
   * One MP4. v = { w, h, timescale, frameDur, avcC, samples: [{ data, key }] }; a = null or { rate, channels, asc, bitrate, samples:
   * [{ data, dur (in samples) }], skip (samples to leave out at the start) }. playMs = how long it plays. Returns a Blob.
   */
  M.make = function (v, a, playMs) {
    var ftyp = box('ftyp', [str('isom'), u32(0x200), str('isom'), str('iso2'), str('avc1'), str('mp41')]);
    var vBytes = v.samples.reduce(function (n, s) { return n + s.data.length; }, 0), aBytes = a ? a.samples.reduce(function (n, s) { return n + s.data.length; }, 0) : 0;
    function moov(vOff, aOff) {
      var mvhd = full('mvhd', 0, 0, [u32(0), u32(0), u32(1000), u32(playMs), u32(0x00010000), u16(0x0100), zero(10), MATRIX, zero(24), u32(a ? 3 : 2)]);
      return box('moov', [mvhd, videoTrak(v, vOff, playMs)].concat(a ? [audioTrak(a, aOff, playMs)] : []));
    }
    var size = moov(0, 0).length, mdatAt = ftyp.length + size, head = mdatAt + 8;
    var mv = moov(head, head + vBytes), mdat = cat([u32(8 + vBytes + aBytes), str('mdat')]);
    var parts = [ftyp, mv, mdat]; v.samples.forEach(function (s) { parts.push(s.data); }); if (a) a.samples.forEach(function (s) { parts.push(s.data); });
    return new Blob(parts, { type: 'video/mp4' });
  };
})(typeof self !== 'undefined' ? self : this);
