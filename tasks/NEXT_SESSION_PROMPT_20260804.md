FadCam / Joy Creator — C:\+Projects\Screenrecorder\FadCam, branch `joy-creator`.

**Supersedes NEXT_SESSION_PROMPT_20260803.md.** Read `tasks/LEDGER.md` FIRST. Never delete an
entry to shorten it — strike through and correct in place.

**JoyRaptor is not a developer and has said so (2026-08-03): he is trusting the agent to apply best
practice.** Make the ENGINEERING calls yourself; bring him PRODUCT/UX decisions only. He cannot
check the work, which is a reason to be MORE rigorous, not less.

---

## THE LESSON OF THIS SESSION — read before writing any code

**A green harness is not a working feature.** M11 anchoring shipped with 35 passing checks against
the real model classes, a debug probe, and a commit message saying "the shift lands, proved
against the REAL model classes". It was **completely inert in the app**: nothing ever called
`attachOverlayToHostUnderStart`, so `hostClipId` was null for every overlay in every project and
`applyAnchorShift` returned on its first line every time. Two adversarial review agents, pointed
at the night's work and told to break it, found that plus **20 other confirmed defects** —
including a mask dialog that punched a permanent hole in the user's PiP merely for being *opened*.

**So: after building anything, run an adversarial pass over it before believing it.** The reviews
cost ~20 minutes and were worth more than the code they reviewed. Ask specifically "who CALLS
this?" — an unreachable feature passes every test it has.

## WHAT SHIPS NOW (device-proven unless marked)

- **§2d FIXED — overlays no longer drift past a transition.** PiP onset 5.50s → **4.90s** on the
  fixture, prediction frozen in a commit beforehand. The formula that looks right and is NOT:
  `editorStart − compressedStart` is ZERO for the clip after a seam (a transition eats its HEAD,
  it does not move its start). Correct quantity = **cumulative transitions at seams BEFORE the
  clip**. A one-line `DRIFTDIAG` log settled in one export what two rounds of reading did not.
- **§2e FIXED — image clips no longer kill the export.** Root cause read from media3's source:
  `getImageMimeType` resolves a `file://` type from the FILE EXTENSION, and Faditor's copied
  assets (`asset_…_image:127376`) have none, so stills were routed to the VIDEO loader. Now the
  MIME is sniffed from magic bytes and declared. Same fixture: died at 8126ms, now 13.726s clean.
- **§3i M12 SHIPS — clips move between spine and layer, both ways.** The Move drawer's ↑/↓ (which
  were "coming soon" toasts). Device-proven from `project.json`: `clips 3→2`, `overlayClips 1→2`,
  `layerId=video`, position preserved, audio carried; full round trip restores byte-identical id
  order. **The DRAG is still unbuilt** — the spine is not in the row-gesture pipeline, so that is
  a new path, not an extension (§3A).
- **Captions no longer lag** by the head transition (they were the only thing left wrong after
  §2d, which is worse than everything being wrong together).
- **New doors on doorless engines:** text outline/glow/shadow/background plate; PiP **Mask**
  (one box + feather, previews live); PiP **blend mode** (export-only, labelled as such).
- **M11 anchoring is now actually wired** (creation + gesture-finish) — but see UNVERIFIED below.

## ⚠ UNVERIFIED — do these first

1. **That the anchor attach FIRES at runtime.** Both call sites are in the dex and the logic has
   35 harness checks, but no device run has confirmed an overlay gets a `hostClipId`.
   **HAND TEST:** add a text overlay over clip 2 → `run-as com.fadcam.beta cat …/project.json` →
   expect `"hostClipId"` on it. Then delete clip 1 and confirm the overlay's `startMs` moved left
   by clip 1's duration. If it does, M11 is real; if not, it is still inert.
2. **The text Style controls end to end.** The dialog renders and does not crash (screenshotted),
   but no value has been round-tripped through the UI to `project.json`. Scripted taps kept losing
   the dialog (the keyboard shifts it; ESCAPE dismisses it; the bottom tool row is
   context-sensitive and scrolls). **Route:** long-press the text in the PREVIEW → drag the sheet
   handle up → scroll the action list → **More…**.
3. **The mask dialog on device.** Open it on a PiP, press BACK, and confirm **no hole is left** —
   that is the fix for the worst defect found tonight.

## STILL OPEN — from the adversarial reviews, recorded not fixed

- **Tail-filler overlays get offset 0** (`ExportManager:1055` builds a filler `Clip` that is not in
  the timeline, so `editorTimeOffsetFor` returns 0) → a jump at the last-clip→filler seam in
  projects with transitions AND content past the master track.
- **Loop-BEFORE extension items are over-corrected by one term** — they are emitted before the
  head-trimmed main item, so their correct offset is `Σ head_j for j < idx`, not `≤ idx`.
- **Decoration values are raw pixels**, unscaled between preview (a few hundred px) and export
  (1080p), while `fontPx` does scale — so a glow tuned in the preview is ~2–3× thinner on export.
  Fix by expressing them as a fraction of font size, like the shadow default already is.
- **No undo for text decoration edits** (mask edits now have one).
- **Degenerate skipped items** (`MIN_EXPORT_SEGMENT_MS`) compress the composition by more than
  `Σ effectiveTransitionMs`, so the offset is a MODEL of the cursor rather than the cursor. Exact
  fix: have `buildComposition` record the real `editorStart − cursor` per clip.
  `editorTimeOffsetFor` already takes `compressedStartMs` and ignores it.
- **Master audio trims with `durationMs`, not `effectiveTransitionMs`** (`ExportManager:638`) —
  when a transition is clamped, a clip's own audio desyncs from its own picture. Pre-existing.
- **Preview may have the §2e bug too** — `MasterPlaybackEngine:564` builds the image `MediaItem`
  with no `setMimeType`. If so, the image that now exports fine still fails to DISPLAY. Lift
  `imageMimeTypeOf` somewhere both callers can use it.
- **The orphan-anchor prompt** (§4A) is still unbuilt: deleting a clip that layer objects are
  anchored to currently just stops tracking them. Tri-state pref, new row helper needed, must
  MERGE with the existing `confirmDeleteLinkedPair` dialog.
- **`EditScriptApplier`'s two hand-rolled splits bypass `reanchorAfterSplit`** (`:740`, `:966`).

## HOW TO WORK HERE

Carry forward `NEXT_SESSION_PROMPT_20260731c.md` §"HOW TO WORK HERE" and the 20260803 additions.
New this session:

- **Always target the device explicitly: `adb -s SANDBOX_SERIAL`.** A stale offline
  `emulator-5584` appeared mid-session and broke every unqualified command.
- **`export MSYS_NO_PATHCONV=1`** before any `adb shell /storage/...` in Git Bash.
- **`strings` is not installed.** Dex-scan with `grep -alc` — and always include a NEGATIVE
  CONTROL symbol, which is what caught that the first scan was measuring nothing.
- **Exporting a project bumps its `lastModified` and RE-SORTS the project list.** Identify a
  project by the logcat `Editor loaded saved project:` line, never by row position.
- **One scripted gesture attempt, then stop** (the project's own testing-economics rule). Verify
  state-level truth and write a checklist instead.
- **Live-writing dialogs need `setOnDismissListener`, not just a Cancel button** — BACK,
  outside-tap and rotation all bypass the button.
- Harnesses: `bash tools/jvm-harness/run-anchor.sh` (35), `run-promote.sh` (22),
  `run-matte.sh`, and `AnchorMathTest` (39). Run all four; they are seconds.

## DEVICE RULES — NON-NEGOTIABLE

Note 9 `SANDBOX_SERIAL` is the sandbox. If the Note 20 `REAL_SERIAL` appears, STOP. Launch with
`am start`, **never `monkey`**. Take a device-local backup before touching a project
(`run-as com.fadcam.beta cp …/project.json files/backup.json`) and restore with `cp` inside
`run-as`, never `adb push`. Rotation lock verified `0` at session end; sandbox project
`302da9ac` restored byte-exact (md5 `a74cd91c…`).
