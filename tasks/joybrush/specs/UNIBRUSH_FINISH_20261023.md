# Unibrush: finish before switch-on (Engine Lead, 2026-10-23)

Owner ruling (10-23): finish Unibrush properly, editable lines included, THEN switch it on. Until then
`UnibrushActivation.SHIPPING_ENABLED = false`.

Branch `lead/unibrush-u1` (worktree `C:/Temp/lead-uni`): Codex's `codex/unibrush` (PR 1) + held patches D4-D10 applied on
their own base, merged with main 46eed969a (5 hunks, both sides kept). Core 2700 / androidkit 1168 / app 365 tests green.
Plus c1fe7b31f: Paper / Direction / Wet sliders; Texture and Dry catch the paper's tooth by direction by default.

## What the code says (verified map, 10-23)

The live editable-line store is `NativeLines` (pixels stay the truth; `endStroke` pushes one step with a `LineChange`;
editable lines are a pref, off by default). `RecordedScene` / joint publication have NO production caller (debug probes
only), so Codex's "Lead scene transaction / bound RecordedScene activation" is not a prerequisite: native `endStroke` IS
the publisher for live lines. The real gate is getting a line's frozen tip PNGs through NativeLines, save/open and replay.

## Steps (dependency order; S < 2 h, M ~ half a day, L a day+)

| # | step | state | size |
|---|---|---|---|
| U1 | Bench + Note 9 can run the modes without shipping (debug-only override; bench picks `unibrush.*`; release constant stays false, JVM test) | missing | S |
| U2 | Raster smoke, lines OFF: every mode on the Note 9 vs `StampPreview`; GL errors; RGBA16F colour moments on Adreno | partial | S |
| U3 | `NativeLines` holds the frozen PNGs (assets on add/replace, through undo/redo, 64 MiB cap; changed bytes under one id refused) | missing (transport exists, dropped at `installPendingItems`) | M |
| U4 | Save / open / recover carry the PNGs (`LineSave.assets` -> `LineSaveMapping`, `loadLines` takes assets, `refusalFor` checks the closure first, in-memory write+read self-check before replacing the file) | archive layer done; engine/View missing | M |
| U5 | Replay image and dual lines from NativeLines assets; lift the three refusals together, fail closed (stroke stays pixels + a notice) | missing | S |
| U6 | Taper as raster and as a line (today pen-down refuses it: draws nothing). Provisional stroke, shaped replay at lift in one GL closure, never drop the stroke | missing | M |
| U7 | Select / move / rebrush / recolour image and dual lines (dual lookup, assets staged into the edit) | missing | M |
| U8 | Note 9 acceptance, lines ON, all 11 modes: pen-up parity, one undo per stroke/edit, move/rotate/recolour/rebrush, save + force-stop + reopen without the library + first edit, context loss | missing | M + phone |
| U9 | Pen modes 60 fps (spiral/scribble, lines on/off, ABBA) + a lift-to-final metric | missing | S + phone |
| U10 | Soft 2000 px >= 45 fps on the Note 9; a 2000 px Soft line fits `LineEditBudget` or is refused in words | partial | S |
| U11 | Half-resolution stroke buffer (D10_PLAN), only if U10 fails | missing | L |
| U12 | Switch on: flip the constant, merge-tree vs main, Note 9 first, Note 20 only after U8's reopen check and with a copy of his projects | - | S |

U1-U10 + U12 block switch-on (U10 as a measurement). If U10 misses 45 fps: cap Soft's size at the highest passing size and
build U11 after, or wait: the owner's call.

## The riskiest point (the owner's work)

Lifting the line refusal without U3-U5 makes image/dual lines whose PNGs never reach the save: every save and autosave
throws, or a half-done version writes `strokes.jbs` without its manifest and reopen refuses the WHOLE drawing. Guards: U3-U5
land as one commit with the refusals lifted last; a line is admitted only if NativeLines already holds its assets at
`commitPendingLine` (else pixels + notice); the in-memory write+read check before the real file is replaced (the atomic save
keeps the previous file); round-trip tests with the brush library absent; the Note 20 last (older builds cannot read brush
v18 or the asset entries).

## Notes vs code

- Codex's D6/D7/D9 "Lead scene transaction is the publisher; native endStroke is not a substitute": not so for live lines.
- `StabilizerPreferenceStore.kt` gate wording ("recorded scene or resource publication") names the wrong gate: U3-U5.
- Taper is refused at pen-down outright, not only "not a line".
- `UnibrushModes.kt` / `UnibrushModesTest.kt` still say "DRAFT, not compiled": both compile and pass.
