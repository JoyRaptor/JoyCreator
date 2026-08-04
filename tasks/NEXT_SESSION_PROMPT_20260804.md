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

## ⚠ UNVERIFIED — do these first (one of the three is now closed)

1. ~~That anchoring works~~ **FULLY PROVED end to end on device — LEDGER §1n. Nothing owed.**
   Attach fires on creation (`hostClipId` written by the autosave), survives load, shifts
   correctly on a structural edit (3700 → 500, exactly predicted) and is written back.
2. **The text Style controls end to end.** The dialog renders and does not crash (screenshotted),
   but no value has been round-tripped through the UI to `project.json`. Scripted taps kept losing
   the dialog (the keyboard shifts it; ESCAPE dismisses it; the bottom tool row is
   context-sensitive and scrolls). **Route:** long-press the text in the PREVIEW → drag the sheet
   handle up → scroll the action list → **More…**.
3. **The mask dialog on device.** Open it on a PiP, press BACK, and confirm **no hole is left** —
   that is the fix for the worst defect found tonight.

## CLOSED AFTER THE HANDOFF WAS FIRST WRITTEN (same session, later)

- **Preview had the §2e twin** — `MasterPlaybackEngine.addImageWindow` built its image MediaItem
  with no MIME either, so the still that now exports could still fail to DISPLAY. Sniffer lifted
  to `util/ImageMime` with both callers on it; the short-read bug in the sniff fixed too.
- **Tail-filler overlays** now take the full transition total instead of 0.
- **Captions** no longer lag by the head transition.
- **Undo for text decoration** added (mask already had it).
- **The AI's two hand-rolled splits** (`EditScriptApplier` plain split + b-roll cutaway) now
  re-anchor via `Timeline.reanchorAfterManualSplit`.
- **The orphan-anchor prompt** (§4A's last piece) is BUILT: tri-state pref, `setCancelable(false)`,
  announces a remembered silent delete, one undo step. **Its SETTINGS ROW is not** — the sheet only
  has `addSwitchRow` and a tri-state needs a new row helper, so the preference is currently
  reachable only by answering the dialog.
- **The trap constructor** on `BlendModeGlEffect` deleted.
- **Loop-BEFORE items** now carry one transition term less (they are emitted ahead of the
  head-trimmed main item and are themselves untrimmed).
- **Decoration sizes are now a % of TEXT SIZE**, converted by the single authority
  `TextOverlayItem.decorRadiusPx` that both renderers call — the same shape as
  `CompositingSpec.featherRadiusPx`, and for the same reason. Preview and export finally agree.
  Safe as a semantic change because nothing could ever write those fields.
- **The orphan preference has its settings row** (button row + three-choice dialog, including
  "Always ask" so a remembered choice can be un-remembered).

**FINAL REGRESSION EXPORT after all of the above:** 13.726s, zero errors, PiP onset **4.90** —
both export fixes intact. App smoke-tested: launches, editor opens, zero `FATAL EXCEPTION`.
Sandbox `302da9ac` restored byte-exact (md5 `a74cd91c…`), rotation lock `0`.

## STILL OPEN — from the adversarial reviews, recorded not fixed

- **Degenerate skipped items** (`MIN_EXPORT_SEGMENT_MS`) compress the composition by more than
  `Σ effectiveTransitionMs`, so the offset is a MODEL of the cursor rather than the cursor. Exact
  fix: have `buildComposition` record the real `editorStart − cursor` per clip.
  `editorTimeOffsetFor` already takes `compressedStartMs` and ignores it.
- **Master audio trims with `durationMs`, not `effectiveTransitionMs`** (`ExportManager:638`) —
  when a transition is clamped, a clip's own audio desyncs from its own picture. Pre-existing.
- **The orphan prompt's SETTINGS ROW** — pref and accessors exist; needs a tri-state row helper in
  `FaditorSettingsBottomSheet` (its own comment anticipates one). Also still to do: merge with the
  existing `confirmDeleteLinkedPair` dialog when both would fire on one delete.

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
