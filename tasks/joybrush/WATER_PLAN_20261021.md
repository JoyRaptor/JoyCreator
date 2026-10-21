# Water plan, 2026-10-21 (JoyBrush Lead)

The owner, after painting on the Note 9 with the atlas (10-21 morning): creases where an S-shaped zigzag and a sweep back up
crossed; a long blank start, then the stroke all at once; "ran out of space" and a crash after about 15 strokes; finger
daubs that would not undo and later grew a crease; his daughter and wife confused when the pen did nothing with a brush
selected. "We need water to be a wonderful experience ... people will paint zoomed out ... GET IT WORKING."
Codex's review agrees on the causes and adds three (C:/Users/JoyRaptor/joybrush-watercolor-brainstorm-20261021.md).

## What the phone showed (evidence: C:/Temp/lead-1020/owner1021)

- Creases = tile-shaped decisions on water: D2 dried single tiles of a moving trail ("a drip's tail dried where it lay"
  dozens of times), and my 10-21 night look shortcut redrew still tiles only a few a frame.
- Crash = the drawing's media store budget (315 MB) refused the write at stroke end; nothing caught it there.
- Slowness = 20-26 wet tiles simulated for over a minute; every new stroke's frames paid for all of it.
- Latency (bench): input to screen, watercolour p95 0.3-0.6 s; even the window is 4-7 fps on water.
- Daubs = after the crash penSeen was false (fingers paint); a second finger COMMITS the daub (cancel = finish), so a
  two-finger-tap undo only removes its own new daub, wipes redo, and re-wakes stopped water.
- Pen does nothing = realistic brush on a layer that holds editable lines (the old watercolour becomes a line when
  "Editable lines" is on); plus a stale "holds realistic paint" check (floatTiles only filled on open).

## Rules

1. No decision about water is ever tile-shaped. Water is settled, paused or coarsened only by whole connected pools, or by
   smooth per-pixel fields (age, distance).
2. The brush shows at once; physics follows on a per-frame budget. Never block the first dab behind old water.
3. Never crash, never drop a stroke silently; never refuse what a user would call a normal painting session.

## Steps

- [x] S1 stroke end never crashes (budget refusal said in words, stroke closed)
- [x] S2 D2 = the oldest whole pool not under the pen, never a tile; tests rewritten
- [x] S3 still-water look shortcut removed (every wet tile's look every frame)
- [ ] S4 crowded bench (`crowd.sh`: six wet strokes on one drawing, flat + tilted, window vs atlas) = the owner's session
- [ ] S5 cancel = real rollback (GlPaintEngine.cancelMediaStroke; MediaCanvas.cancel; JbCanvasView.cancelStroke); pen seen
      remembered across restarts; requestUnbufferedDispatch from API 21
- [ ] S6 walls variants get the OWN/NEAR fast paths (or walls checked only where reached)
- [ ] S7 one budget per presented frame: brush input + its look first; water steps for the pen's pool, then older pools
      (whole pools) in the time left; a resumable queue instead of the recursive split; pen-up never drains a backlog
- [ ] S8 older water dries faster (smooth, by its per-pixel age), so the wet area stays bounded; a WetConstants setting
- [ ] S9 media brush on any layer: lines on the layer become paint in the stroke's undo step (R51); stale check fixed
- [ ] S10 memory: fully dry tiles drop their payload (ground := look) where safe; water stores freed when dry
- [ ] S11 coarse fluid grid (water at 1/2, then 1/4 resolution, pigment and paper at full) - the big speed lever
- [ ] S12 sparse wet regions (occupied 16-64 px blocks, not rectangles)
- [ ] S13 zoomed-out painting: simulation resolution chosen per pool with hysteresis

Acceptance (Codex's, adopted): first visible paint within 2 frames p95 warm; p95 frame < 33 ms on the Note 9; no >100 ms
stalls; 100-200 strokes on one drawing, flat and tilted, at 100/50/25 %, with no refusal; no grid lines ever.
