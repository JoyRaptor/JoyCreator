# Unibrush U8, tweens and animation clips: read-only scout — 2026-10-27

Scope: read-only. No Gradle, adb, or git writes. Canonical checkout `C:/Temp/jb-select-reconcile-20261024`, branch `lead/select-reconcile-20261024`, HEAD `64ffc8b2d` (committed 2026-10-27 14:33 -0400). Clock at scout time: 2026-10-27 14:35 -0400.

Tags: **[verified in code/git]** = checked against refs, file trees or commit contents in this session. **[doc only]** = taken from a document; not re-executed or re-checked.

Naming collision: "U8" means two different things. Unibrush U8 is the Note 9 lines acceptance matrix (`tasks/joybrush/LEAD_PLAN_20261023.md:32`; plan row at `UNIBRUSH_FINISH_20261023.md` line 28, reachable only via commit `d080fffd3` on `lead/unibrush-u1`) **[verified in code/git]**. Clips U8 is "Drag a clip directly" (`tasks/joybrush/specs/ANIMATION_CLIPS_20261018.md:91`) **[verified in code/git]**. Always name the lane when citing U8.

---

## 1. Unibrush U8

**Gate requirement**
- ROADMAP.md:34 `UNIBRUSH-U6-U12`: "U6 rollback and full U8 matrix/U9-U10 speed/U12 activation remain open; historical source is not current proof." **[doc only]**
- UNIBRUSH_FINISH plan (commit `d080fffd3`, file `tasks/joybrush/specs/UNIBRUSH_FINISH_20261023.md:28`): "U8 | Note 9 acceptance, lines ON, all 11 modes: pen-up parity, one undo per stroke/edit, move/rotate/recolour/rebrush, save + force-stop + reopen without the library + first edit, context loss | missing | M + phone". Line 34: U1-U10 and U12 block switch-on. **[verified in code/git]**
- LEAD_PLAN_20261023.md:32: U8 "Note 9 lines acceptance (bench case agent stopped at the usage limit, nothing written)". **[verified in code/git]**
- JOYBRUSH_CHECKLIST_20261025.md:63-65: "U8 is unfinished bench source and candidate build evidence, not the full acceptance matrix." **[doc only]**
- The U8 focused gate is 2 suites / 17 tests: `UnibrushProbeLifetimeTest` (7) and `UnibrushParityEvidenceTest` (10). Exact command is in `UNIBRUSH_U8_FOCUSED17_GATE_20261027.md` on `codex/unibrush-u8-guards-20261026`. Status line: "SOURCE ONLY. Tests17 UNRUN." **[verified in code/git]** (doc text read from branch; tests not run).

**Branches**

| Branch | Tip | Ahead of HEAD (`git log HEAD..b`) | Patch-unique (`git cherry`) | Ancestor of HEAD | Newest commit |
|---|---|---|---|---|---|
| `codex/unibrush-u8-guards-20261026` | 20fb72e0d | 2 | 2 | NO | 2026-10-27 00:56 -0400, "Close Unibrush source handback with exact focused17 queue gate" |
| `lead/unibrush-u1` | a5e98f5c3 | 31 | 29 | NO | 2026-10-25 00:44 -0400, "Unibrush U7: Select picks, moves and re-brushes picture and dual-tip lines" |
| `codex/unibrush` | 6a5bdae1d | 20 | 20 | NO | 2026-10-21 23:02 -0400, "Finish secondary palette controls and verify combined brush adapters" |

**In HEAD?** No Unibrush code. `git grep -i unibrush HEAD -- joybrush` returns nothing. `UnibrushProbeLifetime`, `UnibrushParityEvidence` and `UnibrushProbeNoticeLease` are absent. **[verified in code/git]**

Source checkpoint `2f119eed2` (per the gate doc) is the U8 guard source, unwired. Original `lead/unibrush-u1@a5e98f5c3` is preserved unchanged.

**Owed**
- Run the 17-test focused gate under the coordinator's CreateNew lock with fresh XML (2 suites / 17 tests / 0 failures). Not run. **[doc only]**
- U6 rollback: cancel-before-admission bug is documented (`reviews/unibrush-recovery-20261026/FINDINGS_EXACT.md`). Not fixed. **[doc only]**
- U8 Note 9 matrix (11 modes, pen-up parity, undo, save/reopen, context loss): no run and no phone grant. **[doc only]**
- U9/U10 speed (Soft 2000 px at 45 fps) and U12 switch-on: open. **[doc only]**
- Owner acceptance: none recorded. **[doc only]**

**Touched in last 6 hours?** No. Newest ref is 00:56 -0400 (about 14 h ago). **[verified in code/git]**

---

## 2. Tweens (frozen-tween, tween runtime, export, wash fidelity)

**Gate requirement**
- ROADMAP.md:28 `TWEEN-LIVE`: "Isolated helper3, export30, clock6 passed. Integrate engine-owned lifetime/GL marshalling, playback scheduling/controls and export; prove exact runtime behavior. Replay-aware watercolor API2ab6cd4c4 is source-only with seven UNRUN tests." **[doc only]**
- ROADMAP.md:27 `FRAME-DUP` (prerequisite): "Next actual GPU/undo/rollback/save proof." **[doc only]**
- VECTOR_TWEEN_INTEGRATION_20261023.md:24 "Bind transient tween rendering" (never write in-betweens to saved cels, `currentFrameId`, or undo). Line 29 "Reconcile overlapping clips/component cels". Line 30 "Complete remaining parent vector requirements". Line 32 "Device and artistic acceptance" (Note 9 list). **[doc only]**
- JB-5.40_tweens.md:66-67: 5.40b "Live UI/undo acceptance remains open"; 5.40c "playback and export draw the in-betweens … Lead, after JB-5.20e". **[doc only]**
- FROZEN_TWEEN_FOCUSED_GATE_20261026.md (branch `codex/tween-wash-fidelity-20261026`): 7 methods in `WatercolourTweenTest`; command `.\gradlew.bat -p joybrush :core:jvmTest --tests cc.joycreator.joybrush.core.anim.WatercolourTweenTest`; "PREPARED ONLY; seven tests remain UNRUN". Blobs: `WatercolourTween.kt` `4fd532421c7cfc8a80bc95314b41c2b4f32425dd`, test `4ce7497a97423298f6b3a08cbf043e3e5096aa35`. **[verified in code/git]** (blob IDs in doc; file names in tree).
- Frame handback (`codex/frame-duplicate-probe-20261026`, `reviews/FRAME_TWEEN_CLAUDE_HANDBACK_20261027.md`): "frozen tween metadata7 remain UNRUN"; live rendering, playback, export remain open. **[doc only]**

**Branches**

| Branch | Tip | Ahead of HEAD | Patch-unique | Ancestor of HEAD | Newest commit |
|---|---|---|---|---|---|
| `codex/tween-wash-fidelity-20261026` | 80ebe1033 | 12 | 12 | NO | 2026-10-26 19:34 -0400, "Prepare exact seven-test frozen-brush tween fidelity gate" |
| `codex/tween-direct-20261026` | ba5e33961 | 13 | 13 | NO | 2026-10-26 14:38 -0400, "Record reviewed frame base fixes and remaining runtime proof" |
| `codex/tween-runtime-20261026` | 08527af67 | 12 | 11 | NO | 2026-10-26 09:22 -0400, "Record preserved component milestone and current canonical ownership" |
| `proof/tween-export-20261026` | f7ed34f16 | 7 | 7 | NO | 2026-10-26 02:34 -0400, "Pin tween exposure ranges reverse holds speed and boundary behavior" |
| `proof/tween-helper-20261026` | e5fa12086 | 5 | 5 | NO | 2026-10-26 02:05 -0400, "Add ownership regression source for prepared editable frame copies" |
| `codex/vector-tweens` | 05474f364 | 4 | 3 | NO | 2026-10-26 09:22 -0400, "Refresh owned broad milestone and redirect historical roadmap" |
| `codex/frame-duplicate-probe-20261026` (handback, not in the list) | 2cc36c481 | not counted | not counted | NO | 2026-10-27 01:03 -0400, "Close frame and frozen-tween source handback with actionable cleanup audit" |

**In HEAD?**
- Present: `anim/Tween.kt`, `anim/TweenFrameSample.kt`, `anim/TweenScene.kt`, `stroke/StrokeWashFrame.kt`, plus their tests. `Frame.tween` and `DOC_VERSION = 14` (`DocModel.kt:27-28, 114`), `setTween` in `AnimOps.kt:384` and `RegionDocumentOps.kt:170`. Commit `59d1f1d63` is an ancestor of HEAD. **[verified in code/git]**
- Absent: `anim/WatercolourTween.kt` and its test (the frozen-brush gate). Also absent: any live tween playback consumer, GL export callback, or saved-tween-to-GPU render. **[verified in code/git]**
- Helper, export and exposure checks (3, 30 and 6 tests) were run on the branches only. Their results are in `TWEEN_HELPER_20261026.xml`, `TWEEN_EXPORT_20261026.xml` and `TWEEN_EXPOSURE_20261026.xml` on `codex/tween-direct-20261026`. **[doc only]** (XML not re-parsed.)

**Owed**
- Run the 7-method `WatercolourTweenTest` on current HEAD. Adopt the two blobs selectively after a dependency check. Not run. **[doc only]**
- Frame CPU oracle (4) and frame GPU oracle on a disposable engine. Not run. **[doc only]**
- GL marshalling and lifetime for the tween consumer (`TWEEN-LIVE`). Not started on HEAD. **[doc only]**
- Playback tick scheduling per exposure (VECTOR_TWEEN_INTEGRATION:25 range), reverse-playback exposures, controls and strip. Open. **[doc only]**
- Export wired before `ExportPicture.asShown` flattening. Open. **[doc only]**
- Phone and owner acceptance. None. **[doc only]**

**Touched in last 6 hours?** No. All tween refs are from 2026-10-26 or earlier, except the frame handback (2026-10-27 01:03, about 13.5 h ago). **[verified in code/git]**

---

## 3. Animation clips

**Gate requirement**
- ROADMAP.md:29 `CLIPS`: "Foundation `59d1f1d63` is present; clips merge `6a4c471eb` is NOT. Preserve parallel `151b92621`; reconcile selectively, retain unknown-key refusal, then combined save/load/export proof." **[doc only]**
- ANIMATION_CLIPS_20261018.md:95-99: Engine C1–C8 (Lead or the Opus helper). UI U1–U8, "U1–U8 do not change engine behaviour." Line 91: "U8 | Drag a clip directly (no confirm), with its drawings; a lock shows it can't move | One undo puts it back." **[verified in code/git]**
- CLIPS_REAPPLICATION_20261027.md (branch `docs/clips-manifest-20261027`): nine NEW production files are absent from the coordinator. Smallest review unit is U-CT1 (`ClipTimeline`), with a proposed `ClipTimelineContractTest` (not written). Warns that adding `Board.end` to the serializer lets the reader recognise it, so no partial build with new-format open/resave. **[doc only]**

**Branches**

| Branch | Tip | Ahead of HEAD | Patch-unique | Ancestor of HEAD | Newest commit |
|---|---|---|---|---|---|
| `docs/clips-manifest-20261027` | 7e122a28a | 1 | 1 | NO | 2026-10-27 00:58 -0400, "docs: pin nine-file clips dependency manifest and reconciliation gate" |
| `lead/anim-clips-integrated` | 151b92621 | 8 | 7 | NO | 2026-10-24 08:21 -0400, "STATE.md: JoyBrush animation clips section (5.6)…" |
| `lead/anim-clips` | 1553c5b45 | 6 | 6 | NO | 2026-10-23 09:37 -0400, "Play all startup stall broken down by chrome patch…" |

Merge `6a4c471eb` is not in this repo's ancestry of HEAD. Commits `151b92621` and `35a9d23b1`/`0a65e19ca`/`9d7adea3f` are not ancestors of HEAD. Foundation `59d1f1d63` is an ancestor of HEAD. **[verified in code/git]**

**In HEAD?**
- Present: `DOC_VERSION = 14`, with the comment "v12/v13 are reserved for animation clips" (`DocModel.kt:27-28`). **[verified in code/git]**
- Absent: `doc/ClipTimeline.kt`, `doc/CelParts.kt`, `anim/ClipPlaybackScheduler.kt`, `export/SceneAnimExportPlan.kt`. The other five of the nine are not checked here; the CSV says all nine are absent. **[verified in code/git]** (4 of 9) / **[doc only]** (remaining 5).
- The U1–U8 UI and the GPU probe are on `lead/anim-clips-integrated` only. The GPU probe log is an 8-row JSON from a feature APK dated 10-22 (`gpu-proof-final-build-2.txt`), all rows `passed:true`. It predates the merge and is not HEAD evidence. **[verified in code/git]** (file read) / **[doc only]** (what it proves).
- Phone screenshots of U7/U8 (`gesture-7-clip-dragged.png`, `gesture-8-clip-drag-undone.png`) exist on `lead/anim-clips-integrated`. **[verified in code/git]** (exists). No owner acceptance is recorded. **[doc only]**
- Inconsistency: `ANIMATION_CLIPS_20261018.md` line 23 on the integrated branch says "Real device screenshots are still outstanding." Line 49 of the same doc describes real screenshots. Treat the screenshot evidence as present but unreviewed. **[verified in code/git]**

**Owed**
- Selective reconciliation of clips with the tween component work (CLIPS row). Not started. **[doc only]**
- U-CT1 `ClipTimeline` review and focused gate (not written). **[doc only]**
- The 5 remaining new production files and the 8-row GPU probe on a current exact-source APK. Not run. **[doc only]**
- Combined save/load/export proof and owner acceptance of U1–U8. None. **[doc only]**

**Touched in last 6 hours?** No. Newest clips ref is `docs/clips-manifest-20261027` at 00:58 -0400 (about 14 h ago). **[verified in code/git]**

---

## Cross-cutting notes

- None of the candidate branch tips is an ancestor of `origin/joy-creator`. `origin/joy-creator` has 13 commits that HEAD lacks. **[verified in code/git]**
- The coordinator handoff and roadmap say all nine specialist lanes are closed and clean. Confirmed for the branches named there. **[doc only]**
- Owner-facing hold: the device is not in an admitted state (readiness question pending per HANDOFF_20261027_COORDINATOR.md:130-132). Any phone step in the recommendations below needs fresh readiness first. **[doc only]**

## Recommended smallest next step per gate (for the Lead)

1. **Unibrush U8.** Admit the 17-test focused gate (`codex/unibrush-u8-guards-20261026`, `reviews/UNIBRUSH_U8_FOCUSED17_GATE_20261027.md`) under the CreateNew lock. Adopt the `2f119eed2` source selectively, only after a root source review. Keep it unwired. Phone U8 waits for readiness and a separate grant.
2. **Frozen tween.** Admit `WatercolourTweenTest` (7 methods) on a current candidate after a selective adopt of the two blobs. Do not replace `Tween.kt`. This is a JVM-only gate and needs no phone.
3. **TWEEN-LIVE.** Smallest honest step is the GL-lifetime and tick-scheduling source review on the `codex/tween-direct-20261026` line, with no UI. Root owns the GL callback binding. Do not expose the tween toggle until rendering is bound.
4. **Clips.** Smallest step is U-CT1: review `ClipTimeline.kt` from `151b92621` and run the proposed `ClipTimelineContractTest` (`-p joybrush :core:jvmTest --tests '*ClipTimelineContractTest*'`). Do not change the serializer until the root refusal policy for `Board.end` is decided.
5. **Clip U8 naming.** Use "Clip U8" or "Unibrush U8" in all future notes to avoid mix-ups.

Do not touch any listed branch while another session may be active. None was modified in the last 6 hours, so this scout found no live-activity conflict.
