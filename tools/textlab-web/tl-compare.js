// Picture comparison for speed work: does a change leave a look's picture as it was?
//
// In the look book's console (or a script): load this file, then
//   await TLC.save(['diamond', 'water'])     keep "before" stills of those looks (in this browser's IndexedDB)
//   ...change the shaders, reload the page, load this file again...
//   await TLC.compare(['diamond', 'water'])  per still: mean and largest difference (0..255), share of values off by more than 8
// Stills are drawn at 1280 x 720 at the moments in TLC.times, with the tilt at rest. Two runs of the same code
// give identical pictures (a few pixels off by a shade on the glass looks), so anything above that is the change.
(function () {
  const open = () => new Promise((res, rej) => {
    const r = indexedDB.open(window.TLC.store, 1);
    r.onupgradeneeded = () => r.result.createObjectStore('px');
    r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error);
  });
  const tx = (db, mode, fn) => new Promise((res, rej) => { const t = db.transaction('px', mode); const r = fn(t.objectStore('px')); t.oncomplete = () => res(r && r.result); t.onerror = () => rej(t.error); });
  window.TLC = {
    store: 'tl-compare',                            // which saved set to use (a name of your choosing)
    times: [3.0, 6.3],
    shot(id, t) {
      const e = TLAPP.engine, gl = e.gl, l = TLAPP.assets.looks.find((x) => x.id === id);
      if (!l) throw new Error('no look ' + id);
      e.setSize(1280, 720); e.setLook(l); e.setText(l.sample || 'TEXT'); e.stage = l.stage === undefined ? 1 : l.stage; e.tilt = [0, 0];
      e.still(t); e.still(t);                       // twice: the first draw after a look change can differ by a shade
      const W = e.W, H = e.H, px = new Uint8Array(W * H * 4);
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, px);
      return { W, H, px };
    },
    async save(looks) {
      const db = await open(), out = {};
      for (const id of looks) for (const t of this.times) {
        const s = this.shot(id, t);
        await tx(db, 'readwrite', (st) => st.put(s, id + '@' + t));
        out[id + '@' + t] = s.W + 'x' + s.H;
      }
      return out;
    },
    async compare(looks) {
      const db = await open(), out = {};
      for (const id of looks) for (const t of this.times) {
        const a = await new Promise((res) => { const r = db.transaction('px').objectStore('px').get(id + '@' + t); r.onsuccess = () => res(r.result); });
        const b = this.shot(id, t);
        if (!a) { out[id + '@' + t] = 'no before'; continue; }
        if (a.W !== b.W || a.H !== b.H) { out[id + '@' + t] = 'size differs'; continue; }
        let sum = 0, max = 0, over = 0;
        const n = a.W * a.H;
        for (let i = 0; i < n * 4; i += 4) for (let c = 0; c < 3; c++) {
          const d = Math.abs(a.px[i + c] - b.px[i + c]);
          sum += d; if (d > max) max = d; if (d > 8) over++;
        }
        out[id + '@' + t] = { mean: +(sum / (n * 3)).toFixed(3), max, pctOver8: +((over / (n * 3)) * 100).toFixed(3) };
      }
      return out;
    },
  };
})();
