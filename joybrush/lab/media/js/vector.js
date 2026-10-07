// vector.js — vector strokes for the media engine. A vector stroke is its RECORD (brush, colour, seed, the
// pen's points after the spline), not pixels. The same physics replays the records into any layer: the
// page at document resolution after an edit, or just the visible region at screen resolution when zoomed
// in, so pencil grain and paint ridges stay sharp at any zoom, and strokes can be recoloured or removed.
//
// Replay is in order (paint picks up what is under it, graphite fills the tooth), deterministic (seeded
// brushes, recorded pen data), and only touches records whose bounds meet the region being drawn.
// Watercolour replays its timing too (how long each wash had before the next stroke), at document
// resolution: its flow is simulated per cell, so its sharpness comes from the paper and edges, not from
// re-simulating at screen resolution.

import { STICKS, DryStroke, stickMaterial } from './stick.js';
import { WET, WET_BRUSHES, WetStroke, paintFromColor } from './wet.js';
import { PASTE_BRUSHES, PasteStroke, opaquePaint } from './paste.js';

export class VectorDoc {
  constructor() { this.records = []; }

  // Start recording a stroke; returns the sink the spline feeds (it also feeds the live stroke).
  begin(meta, liveSink) {
    const rec = { ...meta, samples: [], bounds: null };
    this.pending = rec;
    return s => {
      rec.samples.push({ ...s });
      const pad = 40;
      const b = rec.bounds;
      rec.bounds = b ? [Math.min(b[0], s.x - pad), Math.min(b[1], s.y - pad), Math.max(b[2], s.x + pad), Math.max(b[3], s.y + pad)]
        : [s.x - pad, s.y - pad, s.x + pad, s.y + pad];
      liveSink(s);
    };
  }
  end() {
    const rec = this.pending;
    this.pending = null;
    if (rec && rec.samples.length > 1) this.records.push(rec);
  }

  // Replay every record that meets `region` (doc px, or null = everything) into `engine`.
  replay(engine, paper, pxPerMm, region = null) {
    engine.clearAll();
    let prevEnd = null;
    for (const rec of this.records) {
      const meets = !region || !rec.bounds || !(rec.bounds[2] < region[0] || rec.bounds[0] > region[2] ||
        rec.bounds[3] < region[1] || rec.bounds[1] > region[3]);
      if (rec.kind === 'wet') {
        // Water keeps moving between strokes: give each wash the time it really had (capped).
        if (prevEnd !== null && engine.wetActive) {
          const gap = Math.min(20, Math.max(0, (rec.samples[0].t - prevEnd) / 1000));
          const frames = Math.round(gap / (WET.dt * WET.substeps));
          for (let f = 0; f < frames && engine.wetActive; f++) engine.wetFrame(null, paper, pxPerMm);
        }
        prevEnd = rec.samples[rec.samples.length - 1].t;
        if (!meets) continue;
        const ws = new WetStroke(WET_BRUSHES[rec.tool], paintFromColor(rec.color), paper, pxPerMm, rec.seed, rec.wetness || null);
        rec.samples.forEach((s, i) => {
          ws.add(s);
          if (i === rec.samples.length - 1) ws.finish();
          if (i % 4 === 3 || i === rec.samples.length - 1) engine.wetFrame(ws.take(), paper, pxPerMm);
        });
        continue;
      }
      if (rec.kind === 'paste') {
        const brush = PASTE_BRUSHES[rec.tool];
        // The brush's own history matters (a dirty brush carries earlier strokes), so brushes reload and
        // run through every record in order, even ones outside the region: only drawing is skipped.
        if (!rec.dirty) engine.reloadBrush(opaquePaint(rec.color), brush, rec.seed, rec.belly ? opaquePaint(rec.belly) : null);
        const ps = new PasteStroke(brush, pxPerMm, engine.scale);
        for (const s of rec.samples) { ps.add(s); for (const step of ps.take()) if (meets) engine.pasteStep(step, brush, paper, pxPerMm, rec.seed); }
        continue;
      }
      if (!meets) continue;
      const stick = STICKS[rec.tool];
      const ds = new DryStroke(stick, paper, pxPerMm);
      const mat = stickMaterial(stick);
      rec.samples.forEach((s, i) => {
        ds.add(s);
        if (i % 16 === 15 || i === rec.samples.length - 1) engine.dryFrame(ds.take(), paper, mat, pxPerMm);
      });
    }
    if (engine.wetActive) engine.dryNow(paper);
  }
}
