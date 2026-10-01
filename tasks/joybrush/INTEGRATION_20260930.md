# Joy Brush audit and integration handoff

Started September 30; first fixes completed October 1, 2026.
Owner authorized frontier subagents, engineering fixes and integration without Opus.
Three GPT-6-astra auditors examined persistence, painting and workflow reachability.
Their implementation turns were interrupted by the usage limit. Codex completed and verified
the surviving changes. Existing uncommitted Muse reviews and tuning work were preserved.

## First completed slice

- `CanvasSnapshot.merge` retains the opened document's identity, complete board rectangle,
  board settings and active board (including null), paper options, locked flags, thumbnail,
  and paint/mask cel IDs. Live layer order/settings and pixels replace the corresponding
  projection, with payload addresses remapped together with the cel IDs. Unsupported board
  and animation content remains refused. Wired into `JbCanvasView.readContents`.
- Archive writing now checks declared-to-supplied tile completeness as well as the reverse.
  A missing paint or mask tile is refused before output, preserving an existing healthy file.
- Smudge commit scales every premultiplied channel by stroke opacity. Previously the opacity
  slider was ignored. Shader checks cover transparent and translucent paint and the unchanged
  ordinary coverage branch.
- Save readback failures now belong to their individual request. A timed-out callback cannot
  write later or fail a subsequent request. The readback watchdog stops before disk writing.
- A lost graphics context blocks snapshots and further painting/clearing until a supported
  saved document is reopened. This prevents blank-placeholder autosaves; it does NOT recover
  unsaved marks automatically.
- Layer IDs using the engine's reserved `#mask` suffix are refused before load mutates anything.
  Loaded locked layers refuse new brush strokes and clear. Paper is changed inside the GL load
  transaction, preventing a frame mixing new paper with old pixels.

## Verification

- Standalone androidkit compile and tests: **181 tests, 0 failures/errors**, including 18 new
  snapshot/archive regressions. Archive fixtures are written and read back, not merely inspected.
- Headless Edge/SwiftShader: compiled, GL error 0; smudge, all opacity cases, grain and ordinary
  coverage checks passed. This is a real graphics-driver check, not Note 9 verification.
- Core baseline: **1325 tests, 1 failure**, in the pre-existing UNTRACKED `TuftTuningTest`
  knob enumeration assertion. The newer Sable tuning schema and old tuning work disagree.
  No core files were changed in this slice. Do not call the whole core suite green.
- Running standalone tests while the app watcher rebuilt caused Windows JAR write collisions.
  The isolated androidkit retry passed after the watcher was idle. Do not overlap these builds.
- The app watcher compiled and packaged the first wiring change successfully, but its latest
  rebuild failed with a core JAR write collision and has not refreshed after subsequent saves.
  Final app package is therefore unverified; standalone verification is recorded separately.
- No phone was driven, installed onto, or erased. Device checks remain outstanding.

## Next work, in order

1. **Safe Open transaction.** Read and validate the candidate, wait for the stroke/save queue,
   prevent editing, snapshot and durably preserve the current drawing, THEN replace it. A failed
   preservation must leave the old drawing on screen. The JB-0.08c draft's asynchronous safety
   copy does not gate replacement and cannot meet that promise. Add recent-file access/retention.
2. **Lifecycle recovery.** Startup must finish restore before drawing can start; recover a
   validated `.bak` if the main working file is absent. Add pixel checkpoint recovery on graphics
   restart and lifecycle-aware callback cleanup. Keep the fail-closed snapshot protection.
3. **Runtime document/frame projection.** Boards are export rectangles in ONE unbounded canvas,
   not separate paintings. A board menu alone does not unblock animation. Retain complete
   document/cel payloads, flush the displayed cel before switching, resolve frame-to-cel mapping
   with `DocOps.celFor`, and preserve undo across frame changes.
4. **One complete animation loop.** Create board, animate layer, blank/duplicate/link frames,
   change holds, scrub/play, save/reopen, export GIF; onion skin from the same render source.
5. **Painting reachability.** Fill pen is shipped but currently falls through generic dab drawing;
   selection/transform/flood-fill components have no drawing-screen host. Push is not shipped.
   Wire these deliberately; do not mistake their isolated core tests for a working phone tool.
6. **Studio handoff.** Reuse/extract Studio's existing `createSequenceSheetAndPlace` path and
   pass frame weights. The receiving sequence-placement implementation already exists; the
   Joy Brush sender/receiver contract is missing.

The JB-3.00 draft understates the renderer changes needed and incorrectly suggests separate
per-board art. The JB-0.08c draft merge also loses nonzero board origins and can produce an
invalid active board when the original activeBoardId is null. Do not copy either draft blindly.
Phone priorities: preserve reopened settings, save/reopen painted pixels and masks, verify
Smudge opacity, test background/resume and graphics recreation, then assess non-Sable brush feel.

## October 1: safe Open, recent recovery and PNG export

Owner reports drawing, colour picking, erasing, closing and reopening have worked on the Note.
These ordinary workflows are owner-verified; this does not verify the new safeguards or exports.

- Open now uses the existing save queue: wait for the stroke, block edits, snapshot, durably write
  a separate safety copy, then load the selected drawing. A failed preservation does not replace
  the screen. Unsupported files are checked before entering this transaction. The new drawing
  is then saved as the working drawing. No confirmation dialog was added.
- **Recent drawings** exposes the five prior drawings kept by Open. Preservation failure does not
  prune earlier copies. A copy is retained even if the system clock changes or timestamps tie.
- Startup drawing input waits for restoration. A missing/corrupt working file can restore a
  validated `.bak`, with a recovery message. If neither can be read, autosave leaves them intact.
  Destroyed screens cannot start additional save requests or open a newly read file.
- **Export PNG** is wired through the same stroke-aware queue and the existing RegionRenderer
  and PngWriter. The options offer **Include paper**; otherwise empty art remains transparent.
  Export uses the complete board rectangle, including its signed origin, rather than a screenshot.
  PNG encoding completes before the destination is opened. Export leaves autosave debt unchanged.
- App watcher built successfully after these controls and their wiring were saved. No install or
  phone driving occurred. New phone checks: Open two different drawings and recover the first
  through Recent; export with and without paper; check the PNG dimensions and transparency.
- Verification: **192 androidkit tests, 0 failures/errors**, including seven new history/recovery
  tests and four PNG tests decoded independently by ImageIO. Full app watcher BUILD SUCCESSFUL
  after the test run. Existing core tuning failure from the first slice remains separately recorded.

Automatic graphics-context pixel recovery, frame projection, animation controls and Studio handoff
remain outstanding. The first two old "Next work" entries above are partly superseded by this slice.

## October 1: Note 9 checks and memory/startup fixes

- Installed in place on SM-N960U; backed up the owner working drawing and its backup before tests.
  Initial Open attempt crashed in GPU tile readback: 1711 tiles (~428 MiB) remained in the retained
  opened contents while snapshot allocated another full set. Retain document/thumbnail metadata
  only after upload; live GPU pixels supply the next snapshot. Reuse one direct tile-read buffer.
  Snapshot allocation failure now reports failure without replacing the previous save.
- On the updated phone build, opened fixture A, then B, then recovered A through Recent drawings.
  The preserved owner archive matched every original entry byte-for-byte, including all tile bytes.
  Working fixture preserved arbitrary document/layer/cel IDs and signed board rect (-64,32,128,96).
- Test fixtures exposed paper conversion OR-ing RGB with opaque white, making every colour white.
  Corrected the alpha mask. Final coloured-paper and PNG phone checks are still pending.
- Small-file cold restoration also exposed uploads queued before the first GL surface callback.
  GL work now waits for initial engine creation. Final installed build restored the small fixture's
  red pixels immediately, without the former blank-canvas/context-restart message.
- Verification: 193 androidkit tests, zero failures/errors; metadata-only merge regression writes
  and reopens an archive to prove current pixels replace old pixels while identities/geometry stay.
  App watcher built successfully; final APK includes metadataOf and beforeFirstSurface in its dex.
  Final successful in-place update timestamp: 2026-10-01 08:25:09.
- Standalone test daemon held the shared core JAR and stalled the watcher. Stopped the completed
  test daemon and restarted the single app watcher; final app build successful. Avoid concurrent
  standalone/app builds; timestamp-only touches do not retrigger a failed continuous build.
- Phone control paused after unexpected new pen marks appeared on the scratch drawing; awaiting
  owner confirmation that the phone is free. Owner drawing remains backed up and in Recent.
  PNG with/without paper, final paper-colour check and owner drawing restoration remain owed.

Automatic runtime context recovery remains outstanding. The startup fix prevents premature initial
uploads; it does not claim to recover lost GPU pixels later in the activity's lifetime.

## October 1: export resume fix and completed Note 9 checks

- Owner confirmed new marks were disposable pen tests and authorized continued phone control.
  Kept a local copy of those marks anyway. Coloured paper displays correctly on the phone.
- Actual phone PNG export lost the negative-coordinate half of a painted rectangle. Captured
  its exact input: both tile payloads contained the last tile's pixels, although the saved archive
  remained correct and independent JVM export was correct. GLSurfaceView executes queueEvent
  before EGL is current after resume, even when the context is preserved. A reused read buffer
  made failed GL reads appear to succeed with the preceding tile's bytes.
- All canvas GL actions now enter a FIFO drained by onDrawFrame, which guarantees the current
  context and surface. queueEvent only enqueues work and requests a frame. This also covers
  initial uploads. Tile readback checks framebuffer completeness and GL errors before accepting
  bytes, and unbinds the framebuffer on failure. Actual lost-context recovery remains owed.
- Note 9 proof: paper PNG is 128x96, background RGBA (160,192,224,255), paint (20,60,240,255)
  on both sides of the tile boundary; both captured tile payloads exactly match fixture B.
  Final-build transparent PNG is 128x96, painted bounds (32,16)-(96,48), corner alpha zero;
  all 12,288 decoded pixels match expectations. Temporary capture code removed before final build.
- Added a regression that independently decodes PNGs and checks every pixel across that signed
  boundary for both paper options. **194 androidkit tests, zero failures/errors.** App watcher
  BUILD SUCCESSFUL; final in-place install 2026-10-01 09:30:55. No Note 20 interaction.
- Found an existing watch-build.ps1 outer restart loop plus a second watcher. Killing only its
  child made it restart and compete again. Competition corrupted generated app dex: a green APK
  missed JbColors and crashed at the lobby. Stopped the outer loop and duplicate; preserved the
  generated dex in an ignored build-folder backup, rebuilt, verified actual class definitions in
  the APK, reinstalled and confirmed launch. One continuous watcher remains; do not start another.
- Reopened the original owner drawing through Open. Restored working archive has 1713 entries,
  including 1711 paint tiles: **every entry matches the pre-test backup byte-for-byte**. Drawing
  is visible on the phone. Separate pre-test and pen-test backups remain outside the repository.

Next work: runtime graphics-context recovery, then frame/cel projection, animation controls and
Studio handoff. User already verified drawing, colour picking, erasing and closing/reopening.
