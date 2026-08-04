FadCam / Joy Creator — C:\+Projects\Screenrecorder\FadCam, branch `joy-creator`.

**Supersedes NEXT_SESSION_PROMPT_20260804.md.** Read `tasks/LEDGER.md` FIRST. Never delete an
entry — strike through and correct in place.

**JoyRaptor is not a developer.** Make the ENGINEERING calls yourself; bring him PRODUCT/UX decisions
only. He cannot check the work, which is a reason to be MORE rigorous, not less. **He is actively
using the app on a Note 20 while you build**, so expect live bug reports — capture evidence
(screenshot + `logcat -d` + `project.json`) BEFORE touching anything, because logcat rolls fast.

---

## THE TWO LESSONS OF THIS SESSION

1. **A green harness is not a working feature.** M11 anchoring shipped with 35 passing checks, a
   debug probe and a confident commit message — and was **completely inert**: nothing called
   `attachOverlayToHostUnderStart`, so the field was null everywhere. **Ask "who CALLS this?"**
2. **Adversarially review your own fixes.** Three review rounds found **33 defects**; the third
   round, aimed at the FIXES from the first two, found 12 more — including a regression I had just
   introduced. Budget a review pass after every build phase.

## STATE — everything below is committed, tree clean, all harnesses green

Harnesses (run all five, they take seconds):
`bash tools/jvm-harness/run-anchor.sh` (35) · `run-promote.sh` (24) · `run-matte.sh` ·
`java -cp tools/jvm-harness/out-anchor AnchorMathTest` (39) ·
`java -cp tools/jvm-harness/out5 UndoManagerTest` (40)

### Shipped and device-proven
- **§2d** overlays no longer drift past a transition (5.50s → 4.90s, measured).
- **§2e** image clips no longer kill the export; the same bug is fixed in the PREVIEW too
  (`util/ImageMime`, one authority, two callers).
- **Captions** no longer lag by the head transition.
- **§3i M12** clips move spine ⇄ layer via the Move drawer ↑/↓. Proven from `project.json`.
- **M11 anchoring** proven end to end: attach on creation → persist → load → shift → save.
- **Doors** on: text outline/glow/shadow/plate, PiP Mask, PiP blend mode, orphan-anchor policy.
- **Tap-to-seek latency — FIXED, second attempt.** The first (fast-seek + delayed settle) was
  reverted for making it WORSE. Real cause: `onPlayheadDragFinished` had no did-a-drag guard, and
  `seekToTimelineMs` calls it right after `onPlayheadSeeked` with `onUp` firing it again — so a
  plain tap ran the frame-accurate SETTLE built for drag-scrubbing, paying two extra exact decodes
  on top of its own already-exact seek. Now gated on `wasRealDrag`. **Removes** decodes rather than
  adding them. Not yet felt on the Note 20 — ask JoyRaptor.
- **Caption picker** is opt-in (LEDGER §5's open question, answered by JoyRaptor 2026-08-04).

### Built but NOT device-verified — THE HAND TESTS
`adb`'s `input swipe` interpolates movement, so it cannot express hold-still-THEN-move. These
need fingers:
1. **Hold a master clip → it should show a purple halo + up-chevron. Then pull UP → it leaves the
   spine and becomes a layer.** Release without moving → reorder dialog (unchanged).
   Hold then slide SIDEWAYS → must disarm and do nothing.
2. ~~Double-tap → reorder~~ **REMOVED** — collided with the master clip's existing double-tap
   (the clip-audio drawer), and behaved differently on long vs short clips. Hold-release covers it.
3. **Mask dialog on a PiP → press BACK → NO hole must be left.**
4. **Text Style controls** round-trip a value to `project.json`.

## ⚠ NEXT UP — what JoyRaptor asked for, in his words

> "the remaining things required for cleanly dragging clips onto the main and off. Lift off
> animation and highlighted area where they're going to be inserted so that everything is cleanly
> projected [to] the user so that they don't have to guess."

**DONE:** the lift (purple halo + up-chevron on an armed clip, `drawDislodgeArmedLift`).

**NOT DONE — the insertion highlight.** §3A.4 specifies it: **solid purple line = insert at a
seam**, **red + scissors glyph = this will CUT**, gutter chevron for panning. Today the dislodge is
DISCRETE (vertical pull demotes immediately at the clip's current time); there is no continuous
drag over the spine yet, so there is nothing to highlight into. The right order is:
1. Hand over the in-flight touch to `LayerGestureController` so a dislodged clip becomes an
   ORDINARY picked-up layer item. **This is the key move** — it inherits A9 magnet suppression,
   WYSIWYG drop, edge auto-pan (A1), minimap nav (A2), the overlap resolver and the undo merge,
   all of which already exist. Do NOT build a second drag engine (§3A.5b implementation note).
2. Then the spine as a typed drop target + the §3A.4 indicators.

## STILL OPEN (each with file:line in the reviews)

- **Chroma key has no UI.** Deliberate: the key is export-only and the binding decision
  (2026-07-28) is that a tolerance slider tuned blind is unacceptable. It needs preview parity
  first, which needs the GL compositor — explicitly out of scope in PLAN_LAYERS_V2 until forced.
- **Degenerate skipped items** (`MIN_EXPORT_SEGMENT_MS`) compress the composition by more than
  `Σ effectiveTransitionMs`, so `editorTimeOffsetFor` is a MODEL of the cursor, not the cursor.
  Exact fix: have `buildComposition` record real dropped-content per clip.
- **`FaditorEditorActivity` grew ~900 lines this session** and was already enormous. The mask
  dialog and the decoration controls should become their own classes, matching how
  `ObjectMenuSheet` / `FilterBottomSheet` already live separately. Do this when nothing else is
  mid-flight — a reviewer holding line references makes it painful.
- The stacked-PiP-bands render JoyRaptor saw after two "Move PiP" actions — captured, unexplained,
  not investigated.
(The three fourth-review findings previously listed here — the marker-only reorder restore, the
two silent `onUp` early-returns, and the session-lifetime id set — are all FIXED in `2785d7b`.)

## HOW TO WORK HERE

Carry forward `NEXT_SESSION_PROMPT_20260731c.md` and the 20260803/0804 additions. Critical ones:

- **`adb -s SANDBOX_SERIAL`** always — a stale offline `emulator-5584` broke unqualified commands.
- **`export MSYS_NO_PATHCONV=1`** before `adb shell /storage/...` — BUT it breaks the harness
  scripts' `mktemp`. Run harnesses in a clean shell.
- **NEVER build while an export is running.** A gradle build triggers the repo's install watcher,
  which force-stops the app mid-export and truncates the file. Cost a wasted run.
- **`strings` is not installed.** Dex-scan with `grep -alc`, ALWAYS with a negative-control symbol —
  that is what caught a scan that was silently measuring nothing.
- **Exporting bumps `lastModified` and RE-SORTS the project list.** Identify projects by the
  logcat `Editor loaded saved project:` line, never by row position.
- **One scripted gesture attempt, then stop** and write a hand test. Do not grind synthetic input.
- **Live-writing dialogs need `setOnDismissListener` AND a save on revert** — BACK/outside-tap
  bypass the button, and a 3s autosave debounce means a cancelled edit is often already on disk.
- Device rules unchanged: Note 9 `SANDBOX_SERIAL` is the sandbox; if the Note 20
  `REAL_SERIAL` appears, STOP. `am start`, never `monkey`. Back up `project.json` device-locally
  and restore with `run-as cp`, never `adb push` onto it.

Sandbox `302da9ac` restored byte-exact (md5 `a74cd91c…`); rotation lock `0`.
