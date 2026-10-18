# JoyBrush Lead (fork) handoff — 2026-10-17 late night (usage limit reached)

Read HANDOFF_20261017_LEAD.md first; this adds what the fork lead did after it.

## Branches
- `lead/integration-1018` (C:/Temp/jb-integration-1018) = lines v1.2 (ae59baf6c) + lead/just-works pass 2 (41785d4cf, Haiku
  re-ran: core 2490/0, androidkit 929/0, app 263/0) + origin/joy-creator. No conflict was resolved (two own lines taken out,
  clean merge, re-added: cba702823). Full suites were RUNNING at handoff (scratchpad integ-test.sh; logs verify-engine.log,
  verify-app.log in the worktree). NOT pushed to joy-creator, NOT installed.
- `lead/lines-v12` ae59baf6c = H2–H13 per specs/NATIVE_LINES_V12_HARDENING_20261017.md; agents' snapshots kept in
  tasks/joybrush/held/lines-v12-snapshots-20261017.
- `lead/device-matrix`: first Note 9 run = all 132 cells no-op (bench strokes never reach the canvas; bench bug). A Haiku was
  diagnosing with temporary JoyBrushMatrixDbg logs (C:/Temp/jb-matrix). Check its commits; remove debug logs if left.
- `lead/brush-previews`: ~85% built (real-engine previews, cache, visible rows only). A Haiku was merging origin, fixing the
  BrushPreviewKey name collision (hash of full id) and running preview tests (C:/Temp/jb-previews).
- Opus helper (session local_76d7423a): Atlas B3 steps 3–5, then a Note 9 wet-frame profile, then the watercolour A/B setter
  on the engine (no UI; the lead wires the UI). D2 rule given: the evicted tile is a mirror boundary, never a dry front.

## Adversarial review of ae59baf6c (Opus) — fix before merging to joy-creator
1. CRITICAL: a slabs-only FRAME cel (not the current frame) opens with its slabs missing: loadLines composes with layer.plan
   clips (current frames only). Fix: clip a frame cel to its board rect from the doc at load, or save looks for slab-only cels.
2. MAJOR: slab memory unbounded (every non-line stroke on an item layer keeps 256 KB/tile). Budget it: fold slabs below every
   line into the base, or refuse with a sentence past a budget.
3. MAJOR: stamp brush blend "behind" is kept as a line but commits as normal-over live; refuse "behind" in lineRefusal and
   lineReplayRefusal (or support it live).
4. MAJOR: BoardItemFlatten over-flattens: flatten a plane only if the op's rect (old ∪ new) meets its crossedKeys; a dropped
   frame's lines are deleted, not "became pixels" (fix the sentence).
5. minor: endStroke/replaceTiles: apply items first (they roll back), then tile puts.
6. minor: FrozenBrushCache keyed on NativeLines.revision (bumps every put) — key on a brush-set revision instead.
7. minor: H12 should call requireRecordedGrainAssets(replay.strokeGrain, look, loadedPaper?.paper).
8. minor: copyOf/loadLines drop GPU-widened footprint extras; carry them (open: from saved crossed set).
9. minor: LineBake: copy the current tile as base for any new slab key not already crossed.
10. minor: RGBA8 unit vs float compose ±1 step: measure on the Note 9.
11. minor: deleteLayerStep/duplicateLayerStep should settleLift() first.

## Owner items open
- Brush shelf naming: suggested "Digital Essentials" for the Photoshop-familiar set; asked him: "Premium" word on screen
  (suggest Signature), competitor tier grouped by use (never naming Krita/Procreate/IP), blenders/erasers inside each category,
  "Proto Pencil" is Infinite Painter's brush name. Awaiting his answers.
- His hand test of the 10-16 build items; watercolour A/B demo when the helper's setter lands.

## Rules learned tonight
- Build machine RAM is the bottleneck (often <1.5 GB free); an IDLE Gradle daemon is reused, so 1000–1200 MB is enough to start.
- Never let an agent kill processes by a broad pattern (one did; it killed shell processes). Only PIDs it started.
- Stale jb-phone.lock from VisLab (20:38) was renamed .stale-vislab-2038.
