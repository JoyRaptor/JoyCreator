# Joy Creator Studio — bug and efficiency audit, round 1

**Date:** November 15, 2026. **Mode:** read-only source investigation. **Result:** 27 newly identified findings in this review, plus 5 independently reconfirmed known defects/limits. New means additional to the earlier November 15 launch-audit list, not a claim that nobody has ever mentioned the behavior elsewhere.

**Priority:** prevent loss of work, then preserve animation/rendering correctness, then remove unnecessary work. The 32 ranked entries comprise 5 high, 25 medium and 2 low severity items. Four of the high items are new in this review; one is the previously reported TextLab draft-recovery gap. No S0 finding was established in this pass.

## Read this before interpreting the results

Source investigation only in this round. No app code, roadmap, STATE, job card, device, owner project, Git branch, or GitHub repository was modified. No builds, test suites, benchmarks, fault injection, app launches or device interaction were run by this audit. Existing GitHub build results were inspected. Performance figures below are labelled as calculated, historical or unmeasured.

“Source-confirmed” means the implementation contains the described control flow or arithmetic. It does not establish how frequently a condition occurs on a current phone. Allocation failures, delayed GL execution, multichannel decoder output, and renderer timeouts are conditional triggers. No new runtime PASS/FAIL, measured frame-time, battery, or heat claim is made.

Every included new finding was checked against its concrete source chain by the lead reviewer. Numeric examples were independently recalculated. This is a bounded first audit, not a claim that every screen, brush, shader or combination is now covered.

## Baseline and work already underway

**Final reconciliation:** other sessions advanced GitHub main by 25 commits during this audit, to [3c1c04a0c](https://github.com/JoyRaptor/JoyCreator/commit/3c1c04a0ccb506eb34a318934d4969d65b107d7a). The local checkout advanced independently to `3cfc1a212` and remained free of tracked dirty edits. This audit did not perform those changes. The new commits' changed-file list and the patches touching finding-related files were reviewed; the 32 ranked behaviors remain present. Source links intentionally retain the frozen inspected revisions so their line numbers remain reproducible. Findings are not claims about uninspected future commits.

- Local checkout: `joy-creator` at [078ea1024](https://github.com/JoyRaptor/JoyCreator/commit/078ea10245e1169f5d1bb3e153e1b0b1f0532eb6). No tracked edits were present at initial inspection; existing untracked work was left alone.
- GitHub default branch inspected: [b6ec1320a](https://github.com/JoyRaptor/JoyCreator/commit/b6ec1320a45a3dd46915e89b2c41735205974ae7), eight commits ahead of that checkout. The remote branch SHA was checked directly. No fetch, pull, checkout, merge, reset, commit or push was performed.
- Read the current ROADMAP, STATE, BOARD, agent-start instructions, relevant lessons, JoyBrush/VisLab roadmaps and the launch-month plan. The current direction is finish/fix and preserve work; owner acceptance remains separate from agent checks.
- Compared with the [earlier launch bug hunt](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/tasks/audits/launch_bughunt_20261115.md#L1). Fixed or pending-fixed findings are separated below.
- Studio, JoyBrush, TextLab and other active lanes retain ownership of their files. This audit acquired no code/device/build locks and changed no job cards, roadmap rows or STATE entries.

| Pending GitHub work inspected | What it already addresses | Availability at inspection |
|---|---|---|
| [Export/Studio safety branch](https://github.com/JoyRaptor/JoyCreator/tree/claude/export-safety-20261115) | Earlier audit C1/C2/B6: finalization/cancellation, queued exports, Android service timeouts; E1–E9: blank/empty audio projects, asset insertion, undo refresh, relink cover, restored sheets, backup reopening, audio-drawer rebinding and export notification routing | Three fix commits on the branch, still not integrated at the final main reconciliation |
| [Labs polish branch](https://github.com/JoyRaptor/JoyCreator/tree/claude/labs-polish-20261115-efcd00) | Shared Echo Trails history across cuts, TextLab comment update, VisLab resource strings | Landed during this audit; new TextLab/SongPlayer findings remain present. VisLab selection/import code changes are resource-string substitutions |
| [Capture rooms branch](https://github.com/JoyRaptor/JoyCreator/tree/claude/capture-rooms-20261115-73763d) | Remote authentication, capture low-space follow-through and UI work | Landed during this audit, including authentication and low-space checks; runtime acceptance is not certified here |
| Default-branch bug-hunt landing | Project-delete media preservation, recording entry-point closure, Play update restrictions and earlier small fixes; the initial Play Remote-off stopgap was subsequently lifted after authentication landed | Present on the inspected default branch; not reported as new open defects here |

Branch comparisons establish source availability, not runtime acceptance. They can become stale as other sessions land work.

## Ranking and fix difficulty

| Severity | Meaning |
|---|---|
| S0 | Critical: broad irreversible loss or a similarly severe security failure; immediate stop-ship. |
| S1 | High: work loss/corruption, crash, or a core flow producing substantially wrong results. |
| S2 | Medium: a broken feature/combination or significant efficiency problem with a workaround or narrower impact. |
| S3 | Low: polish, misleading feedback, small inefficiency, or limited presentation defect. |

| Difficulty | Engineering estimate |
|---|---|
| D1 | Very small: under 1 hour. |
| D2 | Small: 1–4 hours. |
| D3 | Moderate: 0.5–2 engineering days. |
| D4 | Substantial: 3–5 engineering days. |
| D5 | Architectural: more than 1 engineering week. |

Estimates include implementation and focused regression verification, excluding build/device queues and owner creative acceptance. They assume familiarity with this code. Related fixes share work: do not add every estimate as an independent project total. Rank is a triage order within this audit, weighing impact, reach and dependencies; severity is the issue's impact class.

| Rank | ID | Room / path | Finding | Severity | Difficulty | State |
|---:|---|---|---|---|---|---|
| 1 | [ENG-01](#eng-01) | JoyBrush wet paint | Cancelling a stroke can erase an existing wet wash after its safety snapshot fails | **S1** | D3 · 1–2 days | New in this review |
| 2 | [LAB-01](#lab-01) | TextLab | Rename discards unsaved edits and undo history | **S1** | D2 · 1–4 hours | New in this review |
| 3 | [LAB-02](#lab-02) | TextLab | Save can mark newer, unwritten edits as saved | **S1** | D3 · 0.5–2 days | New in this review |
| 4 | [ENG-02](#eng-02) | JoyBrush wet-paint history | Undo can advance even when restoring the wet wash fails | **S1** | D3 · 1–2 days; shared work with ENG-01 | New in this review |
| 5 | [KNOWN-LAB-01](#known-lab-01) | TextLab recovery | Unsaved TextLab work has no process-death recovery | **S1** | D3 · 0.5–2 days | Known, reconfirmed |
| 6 | [ENG-03](#eng-03) | JoyBrush realistic-brush symmetry | Short strokes can read symmetry after pen-up has cleared it | **S2** | D2 · 1–3 hours | New in this review |
| 7 | [STUDIO-01](#studio-01) | Studio sprite editing | Splitting an animated sprite does not preserve the right half’s frame timing | **S2** | D3 · 0.5–2 days | New in this review |
| 8 | [STUDIO-02](#studio-02) | Studio recorded avatars | Duplicating an avatar or splitting its right half discards recorded puppet motion | **S2** | D3 · 0.5–2 days including correct split timing | New in this review |
| 9 | [XROOM-03](#xroom-03) | Studio / SpriteLab assets reused by VisLab | Deleting a source project breaks sprites in independently saved VisLab looks | **S2** | D3 · 0.5–2 days | New in this review |
| 10 | [JB-ANIM-01](#jb-anim-01) | JoyBrush animation export | Single-board GIF/PNG exports accumulate timing drift | **S2** | D3 · 0.5–2 days | New in this review |
| 11 | [LAB-03](#lab-03) | TextLab | Photo backgrounds disappear on reopen and standalone export | **S2** | D3 · 0.5–2 days | New in this review |
| 12 | [LAB-06](#lab-06) | TextLab history | Manual word edits bypass toolbar history and leave stale Redo available | **S2** | D2 · 1–4 hours | New in this review |
| 13 | [SP-04](#sp-04) | SpriteLab export | A second numbered-frame export overwrites earlier files and can leave a mixed sequence | **S2** | D2 · 1–4 hours | New in this review |
| 14 | [LAB-04](#lab-04) | TextLab export | Stop can still publish a completed export | **S2** | D2 · 1–4 hours | New in this review |
| 15 | [LAB-05](#lab-05) | TextLab export | Overlapping exports share temporary files | **S2** | D2 · 1–4 hours | New in this review |
| 16 | [XROOM-02](#xroom-02) | SpriteLab / JoyBrush to VisLab | VisLab silently shortens imported holds and truncates long sprite animations | **S2** | D3 · 0.5–2 days | New in this review |
| 17 | [ENG-04](#eng-04) | JoyBrush graphics resources | Partial media-engine initialization leaks earlier successful graphics allocations | **S2** | D3 · 1–2 days | New in this review |
| 18 | [ENG-05](#eng-05) | JoyBrush brush previews | A temporary memory failure permanently disables realistic-brush previews for the renderer | **S2** | D2 · 1–3 hours | New in this review |
| 19 | [JB-ANIM-03](#jb-anim-03) | JoyBrush animation efficiency | A wide zoomed board can regenerate thumbnails indefinitely | **S2** | D3 · 0.5–2 days | New in this review |
| 20 | [SP-05](#sp-05) | SpriteLab export efficiency | Numbered-frame export allocates the entire packed sheet before writing its first frame | **S2** | D3 · 0.5–2 days | New in this review |
| 21 | [XROOM-01](#xroom-01) | Studio / TextLab rendering | A styled-title render timeout silently falls back to plain text | **S2** | D3 · 0.5–2 days | New in this review |
| 22 | [SP-03](#sp-03) | SpriteLab export | “Keep cell size” bakes at the preview’s reduced resolution | **S2** | D3 · 0.5–2 days | New in this review |
| 23 | [SP-01](#sp-01) | SpriteLab / Studio sprites | Reordering a file sequence moves its labels and alignment but leaves its pictures in their old order | **S2** | D2 · 1–4 hours | New in this review |
| 24 | [SP-02](#sp-02) | SpriteLab | Importing a sprite sidecar silently discards every cell tag | **S2** | D1 · Under 1 hour | New in this review |
| 25 | [LAB-07](#lab-07) | VisLab audio | Decoded multichannel audio is written unchanged to stereo output | **S2** | D3 · 0.5–2 days | New in this review |
| 26 | [JB-ANIM-02](#jb-anim-02) | JoyBrush animation export | Long single-board GIF holds are silently shortened | **S2** | D2 · 1–4 hours; shares work with JB-ANIM-01 | New in this review |
| 27 | [KNOWN-ENG-01](#known-eng-01) | JoyBrush save/reopen | Saves above the physical-paint budget retain older paint as pixels only | **S2** | D4 · 3–5 days for durable streaming preservation | Known, reconfirmed |
| 28 | [KNOWN-LAB-02](#known-lab-02) | Library to VisLab | Cold launching a saved VisLab look can select the wrong look | **S2** | D2 · 1–4 hours | Known, reconfirmed |
| 29 | [KNOWN-LAB-03](#known-lab-03) | VisLab audio feedback | A song that cannot decode fails silently | **S2** | D2 · 1–4 hours | Known, reconfirmed |
| 30 | [KNOWN-CAP-01](#known-cap-01) | Capture dual-stream recording | Webcam output does not split when the screen recording rolls over | **S2** | D4 · 3–5 days including long-recording/synchronization checks | Known, reconfirmed |
| 31 | [LAB-08](#lab-08) | TextLab efficiency | The update timer continues running while TextLab is paused | **S3** | D2 · 1–4 hours | New in this review |
| 32 | [SP-06](#sp-06) | SpriteLab previews | Saved-animation thumbnails ignore frame holds and the sheet’s FPS | **S3** | D2 · 1–4 hours | New in this review |

## Detailed findings

### ENG-01

**Rank 1 · S1 · D3 · 1–2 days**  
**Cancelling a stroke can erase an existing wet wash after its safety snapshot fails**  
JoyBrush wet paint. New in this review; absent from the earlier launch-audit list.

**Trigger:** Existing unprinted wash; starting another stroke cannot allocate its rollback snapshot; cancel the new stroke, for example by switching to navigation.

**User impact:** Paint from before the cancelled gesture can disappear. The prior saved file remains whatever the last completed save held.

**Cause:** History.now returns Was.Lost after allocation failure; begin accepts it; cancellation passes it to put, which clears the sheet as though the previous state had been empty.

**Suggested fix:** Refuse new stroke admission if its rollback state cannot be retained. Keep unavailable snapshots distinct from empty sheets and inject failure at every snapshot allocation.

**Proof / limit:** Source-confirmed control flow; not reproduced on a device in this audit

**Source evidence:**

- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/WetSheet.kt:130](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/WetSheet.kt#L130) — capture prior state.
- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/WetSheet.kt:167](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/WetSheet.kt#L167) — accept pending state.
- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/WetSheet.kt:628](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/WetSheet.kt#L628) — failed capture becomes Lost.
- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/WetSheet.kt:650](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/WetSheet.kt#L650) — cancel restores pending.
- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/WetSheet.kt:677](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/WetSheet.kt#L677) — Lost clears sheet.

### LAB-01

**Rank 2 · S1 · D2 · 1–4 hours**  
**Rename discards unsaved edits and undo history**  
TextLab. New in this review; absent from the earlier launch-audit list.

**Trigger:** Open a saved personal look, change its words or knobs, then rename it.

**User impact:** Rename reads the older file and selects that result as fresh; the current editing state and history disappear without a save/discard decision.

**Cause:** askRename reloads a disk-based rename result; fresh selection replaces the document and clears history.

**Suggested fix:** Rename the file/identity while retaining the editing document and undo stack, or save the exact current revision as part of rename.

**Proof / limit:** Source-confirmed control flow; not reproduced on a device in this audit

**Source evidence:**

- [app/src/main/java/cc/joycreator/textlab/TextLabActivity.java:772](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/TextLabActivity.java#L772) — rename callback.
- [app/src/main/java/cc/joycreator/textlab/TextLabActivity.java:399](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/TextLabActivity.java#L399) — fresh selection.
- [app/src/main/java/cc/joycreator/textlab/TextLabActivity.java:481](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/TextLabActivity.java#L481) — fresh resets history.
- [app/src/main/java/cc/joycreator/textlab/TlLookFiles.java:139](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/TlLookFiles.java#L139) — rename reads disk JSON.

### LAB-02

**Rank 3 · S1 · D3 · 0.5–2 days**  
**Save can mark newer, unwritten edits as saved**  
TextLab. New in this review; absent from the earlier launch-audit list.

**Trigger:** Press Save, then keep typing/editing before the queued folder write completes.

**User impact:** The saved file can contain earlier words while the current screen loses its dirty marker. Leaving can then discard the newer work.

**Cause:** The worker captures earlier words and a mutable look; afterSave acknowledges current fields instead of the written snapshot/revision.

**Suggested fix:** Queue an immutable document snapshot and revision; acknowledge only that snapshot and retain the dirty state for subsequent edits.

**Proof / limit:** Source-confirmed control flow; not reproduced on a device in this audit

**Source evidence:**

- [app/src/main/java/cc/joycreator/textlab/TextLabActivity.java:708](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/TextLabActivity.java#L708) — captured write inputs.
- [app/src/main/java/cc/joycreator/textlab/TextLabActivity.java:724](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/TextLabActivity.java#L724) — completion acknowledges current state.

### ENG-02

**Rank 4 · S1 · D3 · 1–2 days; shared work with ENG-01**  
**Undo can advance even when restoring the wet wash fails**  
JoyBrush wet-paint history. New in this review; absent from the earlier launch-audit list.

**Trigger:** Undo/redo requires a differently sized wet-sheet engine and replacement allocation fails.

**User impact:** The current engine has already been released; the wash becomes invisible and the history cursor remains advanced although the restoration did not succeed.

**Cause:** restore releases the old engine before allocating a replacement, returns normally on failure, and put then marks the snapshot live. The shared undo log moved before the sheet callback.

**Suggested fix:** Stage replacement resources, return explicit restoration success, and make the history move/paint restoration transactional. Cover allocation failure and asleep-engine wake failure.

**Proof / limit:** Source-confirmed control flow; not reproduced on a device in this audit

**Source evidence:**

- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/WetSheet.kt:752](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/WetSheet.kt#L752) — release before replacement init.
- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/WetSheet.kt:677](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/WetSheet.kt#L677) — caller assumes success.
- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/WetSheet.kt:666](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/WetSheet.kt#L666) — history restoration.
- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt:6291](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt#L6291) — undo moved before sheet callback.

### KNOWN-LAB-01

**Rank 5 · S1 · D3 · 0.5–2 days**  
**Unsaved TextLab work has no process-death recovery**  
TextLab recovery. Known and reconfirmed: earlier audit A13.

**Trigger:** Edit without saving, press Home, and later have Android terminate/recreate the process.

**User impact:** The unsaved look, words and editing history live only in memory and cannot be recovered.

**Cause:** Creation does not restore a durable editing draft; pause stops rendering/sensors but does not persist the draft.

**Suggested fix:** Persist a versioned draft and restore it explicitly, including current words, look, selected identity and history policy.

**Proof / limit:** Source-confirmed control flow; not reproduced on a device in this audit

**Source evidence:**

- [app/src/main/java/cc/joycreator/textlab/TextLabActivity.java:116](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/TextLabActivity.java#L116) — creation state.
- [app/src/main/java/cc/joycreator/textlab/TextLabActivity.java:1064](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/TextLabActivity.java#L1064) — pause lacks draft save.

### ENG-03

**Rank 6 · S2 · D2 · 1–3 hours**  
**Short strokes can read symmetry after pen-up has cleared it**  
JoyBrush realistic-brush symmetry. New in this review; absent from the earlier launch-audit list.

**Trigger:** Make a short pencil/oil/watercolour stroke with symmetry while GL work is delayed; lift before queued begin executes.

**User impact:** The stroke may paint only its original mark; a later stroke may also change the guide seen by earlier queued work.

**Cause:** A queued lambda reads mutable mediaSymmetry, while finishMediaStroke clears that field synchronously on the input thread.

**Suggested fix:** Capture an immutable per-stroke guide before queuing. Verify using a deliberately delayed GL queue.

**Proof / limit:** Source-confirmed control flow; not reproduced on a device in this audit

**Source evidence:**

- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt:2115](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt#L2115) — mutable guide.
- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt:2197](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt#L2197) — queued begin reads field.
- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt:2216](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt#L2216) — pen-up clears field.
- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt:4160](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt#L4160) — deferred GL execution.

### STUDIO-01

**Rank 7 · S2 · D3 · 0.5–2 days**  
**Splitting an animated sprite does not preserve the right half’s frame timing**  
Studio sprite editing. New in this review; absent from the earlier launch-audit list.

**Trigger:** Split a running sprite preset or an item with timed cell changes at a noninitial frame.

**User impact:** The right half restarts the preset or schedules its cell changes at the old local times, so a split changes the animation instead of preserving the same pictures across the cut.

**Cause:** The right item copies FrameTrack keys unchanged, then changes startMs to the cut. Only transform keyframes are rebased. SpriteFrameResolver evaluates frame keys against timelineMs-startMs.

**Suggested fix:** Preserve the active preset's phase at the cut and rebase subsequent frame keys into the right item's local time. Cover held frames, weighted presets, loops/ping-pong and file sequences.

**Proof / limit:** Source-confirmed control flow; not reproduced on a device in this audit

**Source evidence:**

- [app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java:31666](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java#L31666) — split clones then changes start.
- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteOverlayItem.java:203](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteOverlayItem.java#L203) — copies unchanged frame key times.
- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteOverlayItem.java:274](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteOverlayItem.java#L274) — range does not adjust frames.
- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteFrameResolver.java:57](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteFrameResolver.java#L57) — local frame-clock selection.
- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteFrameResolver.java:83](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteFrameResolver.java#L83) — preset elapsed time.

### STUDIO-02

**Rank 8 · S2 · D3 · 0.5–2 days including correct split timing**  
**Duplicating an avatar or splitting its right half discards recorded puppet motion**  
Studio recorded avatars. New in this review; absent from the earlier launch-audit list.

**Trigger:** Duplicate a sprite-backed avatar with a recorded performance, or split that item.

**User impact:** The duplicate/right half keeps its rig identity but has no performance track, so it no longer replays the authored puppet motion. The original/left item retains its track and Undo remains a recovery path.

**Cause:** SpriteOverlayItem.copyWithNewId explicitly sets avatarTrack=null. Both single/multi duplicate and sprite split call that copier; the drawing path requires a nonnull avatar track to replay the puppet.

**Suggested fix:** Deep-copy AvatarParamTrack, and for a split preserve the performance phase/time range of each half. The existing track JSON codec can support an independent copy; a dedicated typed copy is cleaner.

**Proof / limit:** Source-confirmed control flow; not reproduced on a device in this audit

**Source evidence:**

- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteOverlayItem.java:216](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteOverlayItem.java#L216) — performance deliberately dropped.
- [app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java:31666](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java#L31666) — split caller.
- [app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java:50585](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java#L50585) — duplicate caller.
- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteOverlayView.java:337](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteOverlayView.java#L337) — performance gate.
- [app/src/main/java/com/fadcam/ui/faditor/avatar/AvatarParamTrack.java:122](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/ui/faditor/avatar/AvatarParamTrack.java#L122) — existing serialization/copy basis.

### XROOM-03

**Rank 9 · S2 · D3 · 0.5–2 days**  
**Deleting a source project breaks sprites in independently saved VisLab looks**  
Studio / SpriteLab assets reused by VisLab. New in this review; absent from the earlier launch-audit list.

**Trigger:** Import a project-owned SpriteLab sheet into a VisLab look, save the look, then delete the original Studio project.

**User impact:** The look still points to the removed private asset path. After its renderer/cache reloads, it can display the unrelated built-in Joy record instead of its chosen sprite. Restoring the source project may be a workaround; the look does not own a durable independent copy.

**Cause:** SheetShelf expands project-relative paths to absolute source-project links and pickSheet embeds that JSON without copying assets. Project deletion removes the entire source tree. Missing atlas loading falls back to built-in artwork.

**Suggested fix:** Give reused media shared durable ownership or consolidate it when importing into a look; track dependencies for deletion and show an explicit missing-asset state instead of unrelated art.

**Proof / limit:** Source-confirmed control flow; not reproduced on a device in this audit

**Source evidence:**

- [app/src/main/java/com/fadcam/vislab/room/SheetShelf.java:103](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/vislab/room/SheetShelf.java#L103) — links resolved into source project.
- [app/src/main/java/com/fadcam/vislab/room/VisLabActivity.java:676](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/vislab/room/VisLabActivity.java#L676) — JSON embedded without asset copy.
- [app/src/main/java/com/fadcam/vislab/room/VisLabSession.java:675](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/vislab/room/VisLabSession.java#L675) — look JSON persistence.
- [app/src/main/java/com/fadcam/ui/faditor/project/ProjectStorage.java:964](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/ui/faditor/project/ProjectStorage.java#L964) — source tree removed.
- [app/src/main/java/com/fadcam/vislab/room/Sprites.java:61](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/vislab/room/Sprites.java#L61) — missing atlas becomes Joy record.

### JB-ANIM-01

**Rank 10 · S2 · D3 · 0.5–2 days**  
**Single-board GIF/PNG exports accumulate timing drift**  
JoyBrush animation export. New in this review; absent from the earlier launch-audit list.

**Trigger:** Export a non-tween animation whose frame duration is fractional in milliseconds or GIF centiseconds; for example 30 one-tick frames at 30 fps.

**User impact:** A nominal 1-second 30 fps clip becomes 990ms in PNG timing and 900ms in single-board GIF. At 24 fps the corresponding values are 1008ms and 960ms. At 60 fps the GIF minimum expands it to 1200ms.

**Cause:** Non-tween plans round each frame independently; GIF rounds each result again and applies a 20ms minimum. Scene export and tween millisecond plans already use cumulative boundaries.

**Suggested fix:** Share cumulative-boundary timing across paths. Explicitly resample or warn for GIF cadences above50 fps. Verify complete durations and picture boundaries against PlaybackClock.

**Proof / limit:** Source-confirmed and arithmetic checked; no Android encoder/device reproduction.

**Source evidence:**

- [joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/export/AnimExportPlan.kt:229](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/export/AnimExportPlan.kt#L229) — independent duration rounding.
- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/AnimExport.kt:317](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/AnimExport.kt#L317) — ordinary GIF timing.
- [joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/export/GifEncoder.kt:372](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/export/GifEncoder.kt#L372) — GIF rounding/minimum.
- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/SceneAnimExport.kt:33](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/SceneAnimExport.kt#L33) — existing cumulative implementation.

### LAB-03

**Rank 11 · S2 · D3 · 0.5–2 days**  
**Photo backgrounds disappear on reopen and standalone export**  
TextLab. New in this review; absent from the earlier launch-audit list.

**Trigger:** Pick a photo background, save/reopen the look or export a picture/video/sheet.

**User impact:** The room shows the photo but the reopened/exported look has no photo texture and falls back to black.

**Cause:** The photo is an Activity Bitmap; JSON records stage 11 without a durable photo reference. Independent export renderers receive no photo.

**Suggested fix:** Import the photo durably, persist its reference, and resolve the same asset for room, preview, reopen and export.

**Proof / limit:** Source-confirmed control flow; not reproduced on a device in this audit

**Source evidence:**

- [app/src/main/java/cc/joycreator/textlab/TextLabActivity.java:940](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/TextLabActivity.java#L940) — Activity-only photo.
- [app/src/main/java/cc/joycreator/textlab/TlLookFiles.java:98](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/TlLookFiles.java#L98) — saved JSON inputs.
- [app/src/main/java/cc/joycreator/textlab/LookExport.java:146](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/LookExport.java#L146) — fresh renderer.
- [app/src/main/java/cc/joycreator/textlab/TextLabRenderer.java:217](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/TextLabRenderer.java#L217) — explicit photo upload.
- [app/src/main/java/cc/joycreator/textlab/TextLabRenderer.java:653](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/TextLabRenderer.java#L653) — black fallback.

### LAB-06

**Rank 12 · S2 · D2 · 1–4 hours**  
**Manual word edits bypass toolbar history and leave stale Redo available**  
TextLab history. New in this review; absent from the earlier launch-audit list.

**Trigger:** Change a knob, Undo, type new words, then Redo; or type words and try toolbar Undo.

**User impact:** Word typing creates no TextLab undo step. A stale redo can replace the newly typed words with an older whole-document snapshot.

**Cause:** The words watcher updates fields/rendering without undo.before; only before clears redo.

**Suggested fix:** Group typing into history transactions and invalidate redo when a new manual edit begins.

**Proof / limit:** Source-confirmed control flow; not reproduced on a device in this audit

**Source evidence:**

- [app/src/main/java/cc/joycreator/textlab/TextLabActivity.java:239](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/TextLabActivity.java#L239) — word watcher.
- [app/src/main/java/cc/joycreator/textlab/TlUndo.java:30](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/TlUndo.java#L30) — new-history edit clears redo.
- [app/src/main/java/cc/joycreator/textlab/TextLabActivity.java:583](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/TextLabActivity.java#L583) — whole-document undo/redo restore.

### SP-04

**Rank 13 · S2 · D2 · 1–4 hours**  
**A second numbered-frame export overwrites earlier files and can leave a mixed sequence**  
SpriteLab export. New in this review.

**Trigger:** Write frames twice under the same sheet name, changing frames or exporting fewer the second time; alternatively fail halfway through the second write.

**User impact:** Existing <base>_frames/<base>_NNN.png files are truncated and replaced. Surplus old files remain, and a partial failure leaves old and new frames mixed. There is no replace confirmation or all-or-nothing publication.

**Cause:** doBake reuses a deterministic directory and writeFrames opens deterministic file names with FileOutputStream.

**Suggested fix:** Write each export to a fresh versioned directory, stage it until complete, and report per-frame failures rather than a success-like count.

**Proof / limit:** Source-confirmed; no output files were written in this audit

**Source evidence:**

- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetEditorActivity.java:3068](https://github.com/JoyRaptor/JoyCreator/blob/078ea10245e1169f5d1bb3e153e1b0b1f0532eb6/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetEditorActivity.java#L3068) — reused frame directory.
- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteBaker.java:317](https://github.com/JoyRaptor/JoyCreator/blob/078ea10245e1169f5d1bb3e153e1b0b1f0532eb6/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteBaker.java#L317) — truncating output stream.
- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteBaker.java:340](https://github.com/JoyRaptor/JoyCreator/blob/078ea10245e1169f5d1bb3e153e1b0b1f0532eb6/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteBaker.java#L340) — reused numbered files.

### LAB-04

**Rank 14 · S2 · D2 · 1–4 hours**  
**Stop can still publish a completed export**  
TextLab export. New in this review; absent from the earlier launch-audit list.

**Trigger:** Stop a picture export during render/compression, or any export during final folder copying.

**User impact:** The supposedly stopped job can finish, publish a file and announce success.

**Cause:** Stop sets a flag, but picture creation and final publication/copy do not check it.

**Suggested fix:** Check cancellation before publication and during cancellable copying; stage output and publish atomically after the final cancellation decision.

**Proof / limit:** Source-confirmed control flow; not reproduced on a device in this audit

**Source evidence:**

- [app/src/main/java/cc/joycreator/textlab/LookExportDialog.java:103](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/LookExportDialog.java#L103) — dismiss sets stop.
- [app/src/main/java/cc/joycreator/textlab/LookExport.java:126](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/LookExport.java#L126) — stop flag.
- [app/src/main/java/cc/joycreator/textlab/LookExport.java:173](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/LookExport.java#L173) — unconditional publication.
- [app/src/main/java/cc/joycreator/textlab/LookExport.java:212](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/LookExport.java#L212) — picture path.
- [app/src/main/java/cc/joycreator/textlab/LookExport.java:273](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/LookExport.java#L273) — copy ignores stop.

### LAB-05

**Rank 15 · S2 · D2 · 1–4 hours**  
**Overlapping exports share temporary files**  
TextLab export. New in this review; absent from the earlier launch-audit list.

**Trigger:** Dismiss/stop an export and immediately start another of the same kind before its worker exits, or export from two instances.

**User impact:** One worker can read partially replaced data or delete the other worker's in-progress file.

**Cause:** All jobs use export.png, export.mp4 or export.sprite.png in one cache directory, and every worker deletes that path on completion.

**Suggested fix:** Use a unique per-job temporary directory/file and an explicit export lifecycle.

**Proof / limit:** Source-confirmed control flow; not reproduced on a device in this audit

**Source evidence:**

- [app/src/main/java/cc/joycreator/textlab/LookExport.java:158](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/LookExport.java#L158) — shared video temporary.
- [app/src/main/java/cc/joycreator/textlab/LookExport.java:163](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/LookExport.java#L163) — shared sheet temporary.
- [app/src/main/java/cc/joycreator/textlab/LookExport.java:168](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/LookExport.java#L168) — shared picture temporary.
- [app/src/main/java/cc/joycreator/textlab/LookExport.java:185](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/LookExport.java#L185) — cleanup deletes shared file.

### XROOM-02

**Rank 16 · S2 · D3 · 0.5–2 days**  
**VisLab silently shortens imported holds and truncates long sprite animations**  
SpriteLab / JoyBrush to VisLab. New in this review; absent from the earlier launch-audit list.

**Trigger:** Choose a saved sprite animation in VisLab with a frame hold over 16 ticks or more than 256 unique cells.

**User impact:** A source hold of 60 ticks followed by1tick plays as16+1; new unique cells after 256 are skipped. The imported look changes valid source timing/content without warning.

**Cause:** The VisLab atlas builder expands holds with MAX_HOLD=16 and stops adding new unique cells at MAX_FRAMES=256.

**Suggested fix:** Retain duration metadata instead of duplicating hold indices, and stream/page or clearly preflight unsupported atlas sizes. Never silently substitute a shorter animation.

**Proof / limit:** Source-confirmed control flow; not reproduced on a device in this audit

**Source evidence:**

- [app/src/main/java/com/fadcam/vislab/room/VisLabActivity.java:667](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/vislab/room/VisLabActivity.java#L667) — sheet selection route.
- [app/src/main/java/com/fadcam/vislab/room/Sprites.java:48](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/vislab/room/Sprites.java#L48) — hard limits.
- [app/src/main/java/com/fadcam/vislab/room/Sprites.java:105](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/vislab/room/Sprites.java#L105) — silent skips and hold clamp.

### ENG-04

**Rank 17 · S2 · D3 · 1–2 days**  
**Partial media-engine initialization leaks earlier successful graphics allocations**  
JoyBrush graphics resources. New in this review; absent from the earlier launch-audit list.

**Trigger:** Texture/FBO allocation fails partway through creating a realistic-media engine.

**User impact:** Cleanup can throw before deleting earlier allocations; repeated retries worsen graphics-memory exhaustion until context recreation.

**Cause:** Resources are acquired inside expressions assigned to lateinit arrays only after success. release dereferences those arrays before deleting textures, and marks itself released before that work. FBO completeness failure also leaves its generated name owned.

**Suggested fix:** Record every acquisition immediately, transfer ownership only after complete initialization, and make partial cleanup idempotent. Fault each acquisition and check resource ownership.

**Proof / limit:** Source-confirmed control flow; not reproduced on a device in this audit

**Source evidence:**

- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaLayerEngine.kt:202](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaLayerEngine.kt#L202) — lateinit state.
- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaLayerEngine.kt:270](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaLayerEngine.kt#L270) — partial brush allocation.
- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaLayerEngine.kt:304](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaLayerEngine.kt#L304) — partial state allocation.
- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaLayerEngine.kt:2234](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaLayerEngine.kt#L2234) — cleanup fails before texture deletion.
- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaGl.kt:270](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaGl.kt#L270) — FBO failure cleanup gap.

### ENG-05

**Rank 18 · S2 · D2 · 1–3 hours**  
**A temporary memory failure permanently disables realistic-brush previews for the renderer**  
JoyBrush brush previews. New in this review; absent from the earlier launch-audit list.

**Trigger:** Graphics memory temporarily runs out while initializing a pencil/oil/watercolour preview.

**User impact:** Subsequent realistic-brush previews stay blank even after memory pressure clears.

**Cause:** The catch for IllegalStateException includes GraphicsMemoryException and caches mediaUnsupported=true; subsequent previews return immediately.

**Suggested fix:** Separate permanent capability checks from transient allocation failures; permit retry after memory recovery and context recreation.

**Proof / limit:** Source-confirmed control flow; not reproduced on a device in this audit

**Source evidence:**

- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/preview/BrushPreviewRenderer.kt:320](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/preview/BrushPreviewRenderer.kt#L320) — sticky early return.
- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/preview/BrushPreviewRenderer.kt:344](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/preview/BrushPreviewRenderer.kt#L344) — all IllegalStateException cached.
- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt:203](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt#L203) — allocation error subtype.
- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaGl.kt:255](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaGl.kt#L255) — transient allocation throw.

### JB-ANIM-03

**Rank 19 · S2 · D3 · 0.5–2 days**  
**A wide zoomed board can regenerate thumbnails indefinitely**  
JoyBrush animation efficiency. New in this review; absent from the earlier launch-audit list.

**Trigger:** Select a zoomed animation board with more than 327 distinct frame thumbnails inside its projected full board width, even when many are physically off-screen.

**User impact:** 16-frame GL thumbnail batches can keep rendering while idle: new results evict previously requested thumbnails, and the next refresh requests those again. Device frame/battery impact is unmeasured.

**Cause:** Visibility is tested against full projected board width rather than the actual screen viewport; demanded thumbnails can exceed the8 MiB LRU cache. Completion immediately refreshes and requests again.

**Suggested fix:** Intersect demand with the transformed screen viewport plus bounded prefetch. Bound rescheduling when demand exceeds cache capacity; check rotated boards.

**Proof / limit:** Source-supported unbounded rescheduling condition.327 threshold is calculated from80×80×4bytes and8MiB; not device measured.

**Source evidence:**

- [joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardRuntimeController.kt:97](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardRuntimeController.kt#L97) — 8MiB cache.
- [joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardRuntimeController.kt:402](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardRuntimeController.kt#L402) — full board width.
- [joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardRuntimeController.kt:413](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardRuntimeController.kt#L413) — request filter.
- [joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardRuntimeController.kt:422](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardRuntimeController.kt#L422) — completion inserts then reschedules.

### SP-05

**Rank 20 · S2 · D3 · 0.5–2 days**  
**Numbered-frame export allocates the entire packed sheet before writing its first frame**  
SpriteLab export efficiency. New in this review.

**Trigger:** Write numbered PNG frames for a long imported image sequence, such as 500 square 512px frames with zero padding.

**User impact:** writeFrames first calls bake and holds about 504 MiB of RGBA sheet pixels (504 slots at 8 columns), plus caches and a cropped frame. It can fail despite the bounded sequence cache and despite individual frames being small.

**Cause:** SpriteBaker.writeFrames calls bake, then slices the resulting giant bitmap. Streaming output is possible without that aggregate allocation.

**Suggested fix:** Make a measurement pass for common framing, then render/write one frame at a time into a reusable bitmap. Add a dimension/byte preflight for actual packed-sheet export.

**Proof / limit:** Allocation path confirmed; memory figures calculated, peak memory not measured

**Source evidence:**

- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteBaker.java:233](https://github.com/JoyRaptor/JoyCreator/blob/078ea10245e1169f5d1bb3e153e1b0b1f0532eb6/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteBaker.java#L233) — single packed allocation.
- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteBaker.java:333](https://github.com/JoyRaptor/JoyCreator/blob/078ea10245e1169f5d1bb3e153e1b0b1f0532eb6/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteBaker.java#L333) — numbered output calls bake first.
- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteBaker.java:344](https://github.com/JoyRaptor/JoyCreator/blob/078ea10245e1169f5d1bb3e153e1b0b1f0532eb6/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteBaker.java#L344) — extra per-frame bitmap.

### XROOM-01

**Rank 21 · S2 · D3 · 0.5–2 days**  
**A styled-title render timeout silently falls back to plain text**  
Studio / TextLab rendering. New in this review; absent from the earlier launch-audit list.

**Trigger:** A TextLab title's GL render takes more than 5 seconds or the waiting export thread is interrupted.

**User impact:** The export can continue successfully with plain words rather than the authored title look. The timed-out GL request still runs and may allocate an unused late bitmap; sustained delays also waste queued work.

**Cause:** StudioTextLab.renderBlocking logs timeout but returns null without cancelling/discarding the posted request. CompositeExportOverlay treats null as permission to use the plain-text fallback.

**Suggested fix:** Use a cancellable render ticket and reclaim/discard late results. Propagate rendering failure to export rather than silently changing authored styling; reuse existing ticket patterns in StudioVis.

**Proof / limit:** Source-confirmed timeout/fallback behavior; no current-device timeout reproduced. Late bitmaps are not explicitly recycled here; a permanent memory leak is not claimed.

**Source evidence:**

- [app/src/main/java/cc/joycreator/textlab/StudioTextLab.java:120](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/StudioTextLab.java#L120) — unconditional queued render and5s wait.
- [app/src/main/java/com/fadcam/ui/faditor/export/CompositeExportOverlay.java:617](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/ui/faditor/export/CompositeExportOverlay.java#L617) — null means plain words.

### SP-03

**Rank 22 · S2 · D3 · 0.5–2 days**  
**“Keep cell size” bakes at the preview’s reduced resolution**  
SpriteLab export. New in this review.

**Trigger:** Load an 8192×8192 sheet with an 8×8 grid, select Keep cell size and zero padding, then bake or write numbered frames.

**User impact:** A 1024×1024 source cell becomes 256×256: the whole sheet was decoded to 2048 first. A sequence also defaults to a 512px longest-edge decode. The output loses detail without a quality choice.

**Cause:** The bake uses the editor renderer, which is preview-bounded; SpriteBaker.bake chooses cw/ch from cellRectBitmap, not source dimensions.

**Suggested fix:** Use an export-specific renderer or source-region decode and explicit output resolution; keep preview bounds independent from export quality.

**Proof / limit:** Source-confirmed; resolution example derived from code, not a device measurement

**Source evidence:**

- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetRenderer.java:70](https://github.com/JoyRaptor/JoyCreator/blob/078ea10245e1169f5d1bb3e153e1b0b1f0532eb6/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetRenderer.java#L70) — default sequence cap.
- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetRenderer.java:95](https://github.com/JoyRaptor/JoyCreator/blob/078ea10245e1169f5d1bb3e153e1b0b1f0532eb6/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetRenderer.java#L95) — grid decode cap.
- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetEditorActivity.java:2950](https://github.com/JoyRaptor/JoyCreator/blob/078ea10245e1169f5d1bb3e153e1b0b1f0532eb6/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetEditorActivity.java#L2950) — Keep cell size control.
- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetEditorActivity.java:3058](https://github.com/JoyRaptor/JoyCreator/blob/078ea10245e1169f5d1bb3e153e1b0b1f0532eb6/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetEditorActivity.java#L3058) — bake reuses preview renderer.
- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteBaker.java:210](https://github.com/JoyRaptor/JoyCreator/blob/078ea10245e1169f5d1bb3e153e1b0b1f0532eb6/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteBaker.java#L210) — output size comes from decoded cell.

### SP-01

**Rank 23 · S2 · D2 · 1–4 hours**  
**Reordering a file sequence moves its labels and alignment but leaves its pictures in their old order**  
SpriteLab / Studio sprites. New in this review.

**Trigger:** Open an imported image sequence in SpriteLab, name two frames, and Swap or Ripple them.

**User impact:** The order map follows metadata, but file-backed art is still loaded by the original slot. The displayed name/transform can describe a different picture, and playback/export retain the old art order.

**Cause:** SpriteSheet.cellTransform/cellName map display slots through sourceCell; frameUriAt returns frameUris.get(index) directly. SequenceFrameCache.frame delegates to that unconverted accessor.

**Suggested fix:** Apply the display-to-source permutation exactly once in sequence addressing, keep cache keys consistent with it, and cover reorder/save/reopen/preview/export together.

**Proof / limit:** Source-confirmed; not reproduced on a device in this audit

**Source evidence:**

- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheet.java:283](https://github.com/JoyRaptor/JoyCreator/blob/078ea10245e1169f5d1bb3e153e1b0b1f0532eb6/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheet.java#L283) — sourceCell.
- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheet.java:371](https://github.com/JoyRaptor/JoyCreator/blob/078ea10245e1169f5d1bb3e153e1b0b1f0532eb6/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheet.java#L371) — cellTransform maps source.
- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheet.java:470](https://github.com/JoyRaptor/JoyCreator/blob/078ea10245e1169f5d1bb3e153e1b0b1f0532eb6/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheet.java#L470) — frameUriAt ignores order.
- [app/src/main/java/com/fadcam/ui/faditor/sprite/SequenceFrameCache.java:121](https://github.com/JoyRaptor/JoyCreator/blob/078ea10245e1169f5d1bb3e153e1b0b1f0532eb6/app/src/main/java/com/fadcam/ui/faditor/sprite/SequenceFrameCache.java#L121) — frame cache uses raw slot.
- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetEditorActivity.java:4086](https://github.com/JoyRaptor/JoyCreator/blob/078ea10245e1169f5d1bb3e153e1b0b1f0532eb6/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetEditorActivity.java#L4086) — UI reorder entry point.

### SP-02

**Rank 24 · S2 · D1 · Under 1 hour**  
**Importing a sprite sidecar silently discards every cell tag**  
SpriteLab. New in this review.

**Trigger:** Export a .sprite.json containing cells[].tags, then import it through SpriteLab.

**User impact:** Semantic labels useful to Joybot disappear even though the import reports success; names survive, making the partial loss easy to miss.

**Cause:** The model serializer and parser preserve tags, but importSidecar clears the cell list and reconstructs each cell with index, name and enabled only.

**Suggested fix:** Copy tags when applying the imported cells, ideally through a shared cell-copy routine. Verify one tagged, disabled cell through export/import/save/reopen.

**Proof / limit:** Source-confirmed; not reproduced on a device in this audit

**Source evidence:**

- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheet.java:625](https://github.com/JoyRaptor/JoyCreator/blob/078ea10245e1169f5d1bb3e153e1b0b1f0532eb6/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheet.java#L625) — tags are serialized.
- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheet.java:732](https://github.com/JoyRaptor/JoyCreator/blob/078ea10245e1169f5d1bb3e153e1b0b1f0532eb6/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheet.java#L732) — tags are parsed.
- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetEditorActivity.java:4382](https://github.com/JoyRaptor/JoyCreator/blob/078ea10245e1169f5d1bb3e153e1b0b1f0532eb6/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetEditorActivity.java#L4382) — new cells omit tags.

### LAB-07

**Rank 25 · S2 · D3 · 0.5–2 days**  
**Decoded multichannel audio is written unchanged to stereo output**  
VisLab audio. New in this review; absent from the earlier launch-audit list.

**Trigger:** Choose a file whose Android decoder outputs more than two channels, such as supported 5.1 content.

**User impact:** The output interprets multichannel interleaving as stereo; channel content and timing are wrong, and the analysis ring interprets it differently again.

**Cause:** Decoder channel count is retained, but AudioTrack always uses stereo for counts ≥2; all input samples are written without a downmix.

**Suggested fix:** Negotiate stereo decoder output or implement a channel-layout-aware downmix; feed identical converted PCM to playback and analysis.

**Proof / limit:** Source-confirmed for a decoder returning >2 channels; device/codec compatibility and audible reproduction not tested.

**Source evidence:**

- [app/src/main/java/com/fadcam/vislab/audio/SongPlayer.java:161](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/vislab/audio/SongPlayer.java#L161) — decoder channel count.
- [app/src/main/java/com/fadcam/vislab/audio/SongPlayer.java:242](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/vislab/audio/SongPlayer.java#L242) — stereo mask.
- [app/src/main/java/com/fadcam/vislab/audio/SongPlayer.java:259](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/vislab/audio/SongPlayer.java#L259) — unchanged samples and original frame divisor.
- [app/src/main/java/com/fadcam/vislab/audio/SongPlayer.java:281](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/vislab/audio/SongPlayer.java#L281) — analysis channel mismatch.

### JB-ANIM-02

**Rank 26 · S2 · D2 · 1–4 hours; shares work with JB-ANIM-01**  
**Long single-board GIF holds are silently shortened**  
JoyBrush animation export. New in this review; absent from the earlier launch-audit list.

**Trigger:** Export a legal999-tick frame at 1 fps, or any ordinary held frame longer than 655.35 seconds.

**User impact:** 999 seconds becomes655.35 seconds:343.65 seconds are removed with no warning.

**Cause:** An ordinary hold is submitted as one GIF frame; its16-bit centisecond delay is clamped rather than split.

**Suggested fix:** Split long holds into repeated identical frames with legal delays, reusing the scene-export chunking behavior.

**Proof / limit:** Source-confirmed and arithmetic checked; no device reproduction.

**Source evidence:**

- [joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/RegionDocumentOps.kt:168](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/RegionDocumentOps.kt#L168) — legal hold up to999ticks.
- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/AnimExport.kt:324](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/AnimExport.kt#L324) — one encoder frame per hold.
- [joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/export/GifEncoder.kt:372](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/export/GifEncoder.kt#L372) — silent upper clamp.
- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/SceneAnimExport.kt:35](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/SceneAnimExport.kt#L35) — existing long-delay chunks.

### KNOWN-ENG-01

**Rank 27 · S2 · D4 · 3–5 days for durable streaming preservation**  
**Saves above the physical-paint budget retain older paint as pixels only**  
JoyBrush save/reopen. Known, intentional limitation reconfirmed.

**Trigger:** Save a drawing whose realistic-media payload exceeds the configured save budget.

**User impact:** Visible artwork is retained, but older tiles reopen as set pixels without their original physical pigment/water state. This is not newly discovered loss of visible artwork.

**Cause:** readContents selects newest media payloads within the budget and saves the others as their look alone.

**Suggested fix:** Stream media state durably instead of flattening older payloads, with explicit preservation semantics and bounded readback/writer memory. During this audit, incremental archive carrying landed for working saves: it avoids recompressing unchanged entries, but JbCanvasView's payload-budget filtering remains unchanged and still creates this reopen limitation.

**Proof / limit:** Source-confirmed control flow; not reproduced on a device in this audit

**Source evidence:**

- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt:3346](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt#L3346) — payload filtering.
- [joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt:6981](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt#L6981) — documented newest-first policy.

### KNOWN-LAB-02

**Rank 28 · S2 · D2 · 1–4 hours**  
**Cold launching a saved VisLab look can select the wrong look**  
Library to VisLab. Known and reconfirmed: earlier audit A14.

**Trigger:** From a fresh process, tap a personal VisLab look in Library.

**User impact:** The room can retain its previous/default look because the requested personal look has not loaded yet.

**Cause:** Intent selection scans the initial shelf immediately; personal looks load asynchronously and that requested selection is not retried.

**Suggested fix:** Resolve the requested look after session.whenLoaded and retain a visible failure state if it cannot be found.

**Proof / limit:** Source-confirmed control flow; not reproduced on a device in this audit

**Source evidence:**

- [app/src/main/java/com/fadcam/vislab/room/VisLabActivity.java:138](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/vislab/room/VisLabActivity.java#L138) — selection before asynchronous readiness.
- [app/src/main/java/com/fadcam/vislab/room/VisLabSession.java:564](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/vislab/room/VisLabSession.java#L564) — available readiness hook.

### KNOWN-LAB-03

**Rank 29 · S2 · D2 · 1–4 hours**  
**A song that cannot decode fails silently**  
VisLab audio feedback. Known and reconfirmed: earlier audit C12.

**Trigger:** Choose an unreadable, unsupported or failing audio source.

**User impact:** The picture stops reacting without a useful message, even though source start was initially reported successful.

**Cause:** Decoding occurs asynchronously and failure is logged rather than reported to the room.

**Suggested fix:** Send source-ready/error state back to the room and provide retry/change-source feedback.

**Proof / limit:** Source-confirmed control flow; not reproduced on a device in this audit

**Source evidence:**

- [app/src/main/java/com/fadcam/vislab/audio/SongPlayer.java:75](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/vislab/audio/SongPlayer.java#L75) — asynchronous start.
- [app/src/main/java/com/fadcam/vislab/audio/SongPlayer.java:212](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/vislab/audio/SongPlayer.java#L212) — logged failure.

### KNOWN-CAP-01

**Rank 30 · S2 · D4 · 3–5 days including long-recording/synchronization checks**  
**Webcam output does not split when the screen recording rolls over**  
Capture dual-stream recording. Known roadmap limitation, source reconfirmed.

**Trigger:** Record screen plus separate webcam long enough for screen auto-splitting to roll over.

**User impact:** Screen output becomes several parts while webcam output remains one continuous file; matching segment boundaries are unavailable and the webcam file bypasses the splitting limit.

**Cause:** WebcamEncoderPipeline explicitly has no segment rollover; only the screen pipeline receives the configured split callback.

**Suggested fix:** Implement synchronized webcam rollover with stable effective timestamps, paired names and safe finalization for both storage routes.

**Proof / limit:** Source-confirmed control flow; not reproduced on a device in this audit

**Source evidence:**

- [app/src/main/java/com/fadcam/fadrec/encoding/WebcamEncoderPipeline.java:45](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/fadrec/encoding/WebcamEncoderPipeline.java#L45) — explicit current limitation.
- [app/src/main/java/com/fadcam/fadrec/services/ScreenRecordingService.java:593](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/com/fadcam/fadrec/services/ScreenRecordingService.java#L593) — screen-only rollover callback.

### LAB-08

**Rank 31 · S3 · D2 · 1–4 hours**  
**The update timer continues running while TextLab is paused**  
TextLab efficiency. New in this review; absent from the earlier launch-audit list.

**Trigger:** Put TextLab behind another room/app while its Activity remains alive.

**User impact:** Main-thread callbacks keep waking about 30 times per second and preparing tilt updates. Actual heat/battery impact is unmeasured.

**Cause:** The ticker checks only isFinishing and requeues every 33ms; pause stops GL/sensors but callbacks are cleared only at destruction.

**Suggested fix:** Stop the ticker on pause and restart it once on resume.

**Proof / limit:** Source-confirmed control flow; not reproduced on a device in this audit

**Source evidence:**

- [app/src/main/java/cc/joycreator/textlab/TextLabActivity.java:631](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/TextLabActivity.java#L631) — recurring timer.
- [app/src/main/java/cc/joycreator/textlab/TextLabActivity.java:1064](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/TextLabActivity.java#L1064) — pause omits cancellation.
- [app/src/main/java/cc/joycreator/textlab/TextLabActivity.java:1072](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/app/src/main/java/cc/joycreator/textlab/TextLabActivity.java#L1072) — callbacks cleared at destruction.

### SP-06

**Rank 32 · S3 · D2 · 1–4 hours**  
**Saved-animation thumbnails ignore frame holds and the sheet’s FPS**  
SpriteLab previews. New in this review.

**Trigger:** Make a saved animation with long holds and inherited sheet FPS other than 8; inspect its animated chip.

**User impact:** The chip advertises a different cadence from the actual animation: it advances every frame evenly and falls back to 8 FPS instead of the sheet’s authored rate.

**Cause:** PresetThumb.step uses preset.fps or hardcoded 8; onDraw indexes tick modulo the frame count and never reads weights. Its Once animation also wraps, but whether chips should deliberately restart is a separate preview-design choice, not counted as another defect.

**Suggested fix:** Use the existing shared timing evaluator and sheet FPS for chip previews. Preserve any deliberate thumbnail-repeat policy separately from authored frame timing.

**Proof / limit:** Source-confirmed; visual timing not measured on a device

**Source evidence:**

- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetEditorActivity.java:3650](https://github.com/JoyRaptor/JoyCreator/blob/078ea10245e1169f5d1bb3e153e1b0b1f0532eb6/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetEditorActivity.java#L3650) — hardcoded fallback FPS.
- [app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetEditorActivity.java:3664](https://github.com/JoyRaptor/JoyCreator/blob/078ea10245e1169f5d1bb3e153e1b0b1f0532eb6/app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetEditorActivity.java#L3664) — uniform modulo frame selection.

## Calculated examples and verification performed

These are calculations from the inspected source rules, not app execution:

| Animation | Intended duration | PNG manifest | Single-board GIF |
|---|---:|---:|---:|
| 24 one-tick frames at 24 fps | 1,000 ms | 1,008 ms | 960 ms |
| 30 one-tick frames at 30 fps | 1,000 ms | 990 ms | 900 ms |
| 60 one-tick frames at 60 fps | 1,000 ms | 1,020 ms | 1,200 ms |

- A 999-second hold minus GIF's 655.35-second maximum loses **343.65 seconds** unless split into legal chunks.
- The 8 MiB thumbnail cache fits at most **327** raw 80×80 RGBA bitmaps, before other cache occupants.
- A packed 500-frame square 512px sequence, eight columns and zero padding, allocates 504 slots: **504 MiB** for the aggregate bitmap alone.
- An 8192px sheet decoded to 2048px and split into eight columns produces **256px** decoded cells, despite 1024px source cells.

Existing tests were read where relevant; they were not run. In the animation-export tests, checking agreement with a plan or repeating the per-frame rounding formula does not prove correct whole-animation duration. Future regression checks should use an independent total-duration / picture-boundary oracle.

## Efficiency targets that still require measurement

These are leads, not additional confirmed performance regressions or additions to the 32-item count.

| Target | Evidence | Provisional impact / estimated repair | What would establish it |
|---|---|---|---|
| Brush previews can run past their cooperative frame budget | [BrushPreviewRenderer.kt:112](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/preview/BrushPreviewRenderer.kt#L112): budget checked between complete steps; one-shot render/readback and initial media setup need not yield. [MediaLayerEngine.kt:268](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaLayerEngine.kt#L268) compiles remaining programs synchronously | Potential S2; D3, 1–2 days if measured stalls justify staged work | Capture cold/warm drawer-opening frame times; attribute compile, raster and readback separately |
| Studio leaving/saving may block the main thread on large history | Earlier audit E10 names saveProjectNow(true) and whole-history serialization; this audit did not measure its runtime cost | Potential S2; D3, 0.5–2 days, contingent on source/lifecycle review | Measure Home/Back/save latency with realistic large histories; preserve revision ordering if moved to a worker |
| Board chrome performs work for the full board rather than only the viewport | Frame-list construction and overlap comparisons accompany refresh; sprockets/frame elements can be generated across full width | Related to JB-ANIM-03; no separate severity or effort counted | Profile after fixing the thumbnail demand loop; then cache/cull only demonstrated hot work |

The clear efficiency fixes already in the ranked list are SP-05's aggregate export bitmap, JB-ANIM-03's rescheduling loop, ENG-04's failed-allocation cleanup, and LAB-08's paused timer. They have concrete source mechanisms rather than generic optimization advice.

## GitHub compute and test evidence

Inspected recent public GitHub Actions history, including the [defaultDebug bug-hunt landing build](https://github.com/JoyRaptor/JoyCreator/actions/runs/43747189382) and [Play-release bug-hunt build](https://github.com/JoyRaptor/JoyCreator/actions/runs/43744766461). The twelve recent runs returned successful build conclusions at inspection time.

The [current cloud build workflow](https://github.com/JoyRaptor/JoyCreator/blob/b6ec1320a45a3dd46915e89b2c41735205974ae7/.github/workflows/build.yml#L1) runs assemble/bundle and publishes artifacts. It does **not** run the Studio JVM harness, JoyBrush unit suites or device benchmarks. Its workflow-run HEAD can identify the workflow branch rather than the separately supplied built ref, so a green run title alone is not treated here as exact-source behavioral proof.

No duplicate cloud build was started merely to repeat compilation; it would not exercise these findings. A useful follow-up is a cloud test job for the existing focused suites and independent timing/resource-failure regressions, with device checks kept as a separate acceptance gate. No workflow or source was changed in this read-only round.

## Room-by-room coverage ledger

| Area | Source paths inspected | Remaining coverage |
|---|---|---|
| Studio | Sprite split/duplicate call sites, object deep-copy models, transform/frame local-time adapters, styled-title export fallback, portions of layer-preview visibility and single-frame/export composition; existing launch-audit and pending safety fixes compared | Full timeline touch/selection sweep, every drawer/tool, complex FX/masks, speed/transition/caption combinations, export queue fault injection and device parity remain |
| JoyBrush realistic painting | Pencil/oil/wet-watercolour input dispatch; symmetry queue; ordinary/animation-cel addressing; wet-sheet begin/carry/print/cancel/history; partial GPU resource ownership; preview failures; physical save budgeting | S Pen pressure/tilt feel, shader-by-shader optics, injected GPU failures, long-session memory/thermal behavior, all imported brush formats |
| JoyBrush animation / boards | Creation and frame duplicate/link/blank/delete/reorder/hold operations; held/animated layers; board move/resize/combine; single/multi-clip clocks; end modes; GIF/PNG/sheet/scene export; line flattening and thumbnails | Native/media frame-duplicate pixel equality, mixed masks/INK/lines, cancel/undo/reopen equality, visually correct playback and every brush-board combination |
| TextLab | Selection, word editing/history, save/save-as/rename/delete, photo/font import, lifecycle, stage/photo renderer, standalone export generation/copy/cancel | All look/shader visual outcomes; actual gesture/tilt input; device round trips and GPU timing |
| VisLab | Cold selection and async session loading; song decoder/playback; sprite import/atlas/timing; look serialization and media references | Complete element/knob matrix, microphone/live/system audio, MIDI/MOD, long thermal sessions, every Studio bridge combination |
| Cross-room transfers | TextLab→Studio style rendering; Studio/SpriteLab→VisLab timing and asset ownership; board→Studio receiver was explored by a reviewer, but no further verified receiver defect is included | Full board handoff cancellation/recreation/folder-change matrix, font/pack/asset lifetime and dependency-aware delete/restore |
| SpriteLab | Sequence frame addressing/reorder; sidecar tags; decode bounds; bake/numbered-frame output, overwrite and allocations; animated chip timing; relevant Studio entry routes | Device alignment/colour-key checks, mixed-dimension source sequences, file-system write failure injection, every sheet/rig operation |
| Capture / Remote / Joybot / shell | Roadmap and previous audit reconciled; known dual-stream rollover source checked; pending Remote/Play fixes compared | These rooms did not receive a new full deep implementation sweep in this pass; retain them for a separate round |

### JoyBrush combination ledger

“Traced” below means source reviewed, not executed successfully.

| Combination | This round's inspection | Required proof still missing |
|---|---|---|
| Raster/editable lines + Image/Animation boards | Board/frame ownership and export-as-shown adapters traced | Draw→duplicate→undo→save/reopen→export pixel equality |
| Pencil/oil + ordinary and animation cels | Media-plane dispatch and save addressing traced | Frame isolation and exact duplication under memory pressure |
| Wet watercolour + animation frames / changing layer | Plane changes, sheet settling/carrying, cancel and history traced | Water-state preservation through frame switches, undo/redo and reopen |
| Realistic brushes + symmetry | Input→queued-begin chain traced; ENG-03 found | Short taps and real S Pen pressure/tilt under delayed GL |
| Sprite boards + roll/swap + mixed media | Board/export paths read; no complete transaction acceptance asserted | Swap two cells holding raster, lines, media and masks; undo/redo and export equality |
| Image tile mode + brush-family changes | Entry/guard paths considered; current docs contain historical refusal/disarm wording | Boundary strokes, brush-switch behavior, cancellation and repeated-pattern export |
| Masks/hidden layers + frame/board operations | Existing guards and scope boundaries considered | A complete mask/hidden-layer × brush × board matrix was not executed |
| Paper appearance/Bite + brushes | State/roadmap acceptance limits read | Visual/physical response across papers and brushes remains a separate optics gate |

Core tween helpers are present, but live playback integration remains an explicitly unfinished roadmap item. It is recorded as an existing acceptance gap rather than a newly discovered regression.

## Unresolved behavior / validation leads

- **TextLab→Studio word editing:** TextLab returns edited words in a look's `sample`, while the Studio callback applies the look and continues using the title's existing text. The behavior was traced, but this round did not establish whether that screen intentionally edits sample wording only. **Potential S2, D2 (1–4 hours)** if actual title wording is meant to return. Clarify the contract before calling it a defect; it is not counted among the 32.
- **Previously reported floating-chat work loss:** earlier audit A10 deserves an exact-current-source recheck against active Joybot branches. It was not independently re-proved here and is not claimed as new.
- **Older JoyBrush screen overwriting a newer one (earlier A6):** a WorkingFileOwners guard landed during this audit. It checks ownership before working saves and reloads an older screen on resume. The new source was reviewed as a status change, not runtime-proved here; do not keep advertising the prior path as wholly unfixed.
- No conclusion of “works correctly” is drawn from areas where no issue was found in this source pass.

## Recommended repair order and next hunts

1. Repair TextLab rename/save revision handling and wet-sheet failure transactions (LAB-01/02, ENG-01/02); add loss-prevention checks before broad stress testing.
2. Preserve Studio sprite/performance continuity and cross-room sprite ownership (STUDIO-01/02, XROOM-03).
3. Unify animation export timing, fix silent transfer truncation, and fix export cancellation/output isolation (JB-ANIM-01/02, XROOM-02, LAB-04/05, SP-04).
4. Bound thumbnail demand and frame-export memory, then profile actual device costs (JB-ANIM-03, SP-05). Repair partial resource cleanup and transient-preview recovery (ENG-04/05).
5. Close remaining room-specific feedback/history/quality defects, then execute the combination ledger on disposable fixtures.

For another **read-only source** round, prioritize Studio's timeline gestures, audio/caption cut timing under speed and transitions, masks/mattes/FX ordering, and board handoff lifecycle/dependency handling. After that, deep-sweep Capture/Remote/Joybot/shell and the imported brush/pack paths. Keep the IDs in this report stable, recheck the current GitHub tip, and add new findings instead of re-counting these.

For a **runtime** verification round, use disposable test projects and explicit source/build receipts. Start with delayed-GL symmetry taps; existing wet wash plus forced rollback-snapshot failure; undo across wet-sheet size changes with forced replacement failure; allocation failure after each acquired GL resource; and draw→frame duplicate→undo/redo→reopen→export comparisons across the board/brush matrix. This report grants no claim that those tests have passed.
