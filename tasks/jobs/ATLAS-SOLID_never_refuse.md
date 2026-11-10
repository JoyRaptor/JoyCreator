# ATLAS-SOLID: realistic paint never stops an artist for a technical reason

**Milestone:** M1  ·  **State:** 🟡  ·  **Owner:** Claude Fable 5.2 session "atlas solid" (2026-11-09/10), branch `claude/atlas-solid-20261109`  ·  **Checked:** 2026-11-10 against `origin/joy-creator` `93c1cf76f`

## Why it matters (for the user)
The owner's three bars for JoyBrush (2026-11-09): his wife (a skilled artist, impatient with technology), his toddlers (too
young to recover when something breaks) and himself (a professional). None of them should ever read about memory, megabytes
or "areas", lose a stroke, or see a black square. Pencil, watercolour and oil must simply keep painting.

## Done when
- [x] A drawing whose realistic-paint room is full keeps painting: old paint sets, nothing is refused.
- [x] A small mark far from older paint does not fail.
- [x] A tap leaves a dot with oil and watercolour brushes.
- [x] No raw error text and no megabytes in any sentence an artist can read from the paint engine.
- [ ] The owner paints a long session on the Note 9 and the Note 20 and meets no refusal (his own test).
- [ ] The cause of the 10-31 lost tiles and the failed tile copy is reproduced, or shown gone by a long stress run.
- [ ] Saves stream to disk so the room can grow past 315 MB (then old paint sets far later).
- [ ] Symmetry works with pencil, watercolour and oil in the atlas (today: refused in words).
- [x] STATE.md rows (5.6 and section 9).

## Where it stands (2026-11-10)
On branch `claude/atlas-solid-20261109`. Receipt with the numbers: [ATLAS_SOLID_20261110.md](../joybrush/reviews/ATLAS_SOLID_20261110.md).

| Change | What the artist gets | Proof |
|---|---|---|
| Old paint sets to make room (`MediaRoom`, `GlPaintEngine.setOldMedia`) | A full drawing keeps painting. The least recently painted paint, farthest from the pen, keeps its exact picture and stops being wet-workable. It is part of the stroke's own undo step. | JVM tests; Note 9 probe `roomMade`; Note 9 long sessions with a tiny room |
| The atlas hands back what it only caches when the room is tight (`MediaAtlas.yieldForRoom`) | The same on a phone whose room is smaller than the atlas | Note 9: 40 oil strokes, 24 MB room: 24 refused before, 0 after |
| Far cached tiles are let go (`MediaAtlasPlacement.farToLetGo`, `MediaAtlas.letGoFar`); far walls stay out of the table | A mark far from old paint no longer fails (second review, E7) | JVM tests; Note 9 probe `farMark` |
| A still press reaches the brush (`MediaSpline.finish`) | A tap leaves its dot (second review, E6) | JVM tests (Oil round lays a step). Not looked at on the phone |
| `GraphicsMemoryException`; a tile the driver cannot draw into is refused when made; spare textures given back; later atlases half the size; a failed atlas is retried smaller | No black squares from a tile born without memory; realistic paint is no longer switched off for the session when the big atlas cannot be made | Compiles; existing probes pass. **The failure itself has not been reproduced**, so this path has not run on a phone |
| Sentences | "That stroke stopped early. What it painted is kept: carry on from there." replaces raw error text; no megabytes or "memory" | JVM test on the sentences |

## What is left
- [ ] Owner test on the Note 9 build, then the Note 20.
- [ ] A stress run long enough to meet the 10-31 tile failures again (or not), reading `JoyBrushTiles` and `JoyBrushMediaMemory`.
- [ ] When running water is pinned far from the pen, the stroke is still refused in words ("too far from paint that is still wet"). Rare; the water should be set aside instead.
- [ ] Streaming saves (second review E4), then raise `LayerBudget.MEDIA_RAM_SHARE`.
- [ ] Memory admission for the atlas itself (second review E3): the check before making water still counts the old 1024 window.
- [ ] Symmetry for realistic paint in the atlas.
- [ ] A tile boundary can show where set paint meets live thick paint that is re-lit. Not seen in the runs; ask the owner to look.

## Specs, handoffs and evidence
- [Second review](../joybrush/reviews/CODEX_REVIEW_WAVE2_20261101.md) E3, E4, E6, E7; [MEDIA-SPEED card](MEDIA-SPEED_large_brushes.md) for the 10-31 failures.
- Evidence folder: `tasks/joybrush/evidence/atlas-solid-20261110/`.

## Files and traps
- `GlPaintEngine.writableMediaTiles` (hot file): setting old paint happens there, before anything is swapped. Never offer a tile the
  media tool holds (`mediaRoomKeep`): written back after its payload set, it would lose its ground.
- `MediaAtlas.hold`, `flush`; `MediaAtlasPlacement.table` no longer grows for far walls.
- The bench's `jb_media_budget_mb` now sticks (`JbCanvasView.mediaBudgetHeld`); before, the screen reset it after one stroke, so
  earlier small-room runs through the real screen did not test what they meant to.
