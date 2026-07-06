# LANES.md — live file-lock board for parallel agents

Two agents may work this repo at the same time (Fable/Claude + opencode). This file is
how they avoid clobbering each other. Protocol — no exceptions:

1. **Before EVERY task**, read this file fresh. If any file your task touches appears in
   the OTHER agent's `files:` list while its `status:` is ACTIVE, **SKIP that task**
   (note "skipped: lane conflict" in your progress log) and take the next task whose
   files are free. Come back to skipped tasks later.
2. **Before your first edit of a task**, update YOUR section: set `status: ACTIVE`,
   the date/time, and the exact files you will touch. Save this file.
3. **After the task's commit** (or when you abandon it), set your section back to
   `status: IDLE` and clear the files list. Never leave a stale ACTIVE claim when you
   stop working — a dead claim blocks the other agent for hours.
4. This file is coordination state, not history — keep each section tiny, overwrite it
   freely, and DO NOT commit it with unrelated changes (committing it is optional;
   the on-disk state is what matters).
5. `git status` is the fallback truth: if the other agent's claimed files are dirty in
   the tree, treat the claim as live even if the timestamp is old.
6. **SOLE BUILDER: never run gradle/gradlew.** The user's watcher is the ONE builder.
   Compile-verify by SAVING and reading `build.log` for a fresh `BUILD SUCCESSFUL`.
   Two gradle processes corrupt the resource merge (missing `.flat` → "100 class R
   errors"). If `build.log` won't update, STOP and tell the user — never invoke gradle.
7. **DEVICE is single-owner.** Before ANY device command (`am start`, `input`,
   `screencap`, `screenrecord`, `logcat`), read the `DEVICE:` token below. Take it only
   when it reads `free` (or the holder is IDLE): set `DEVICE: <you>`, do your batch,
   then set it back to `free`. Never drive the device while the other agent holds it.

## DEVICE TOKEN
DEVICE: free
(Fable owns it during hard interactive device-loops; opencode does code+compile-only
and BATCHES its device-verify into windows when this reads `free` / Fable is IDLE.)

**Standing (permanent) Fable locks — never touch regardless of this board:**
`export/ExportManager.java`, `export/BlendModeGlEffect.java`, `export/PipFrameOverlay.java`,
`export/CompositeExportOverlay.java`, `project/ProjectStorage.java`,
`compositor/MasterPlaybackEngine.java`, `compositor/OverlayVideoPreviewView.java`,
`compositor/LayerPreviewController.java`, `compositor/DecoderBudgetProbeActivity.java`,
`avatar/PuppetPoseResolver.java`, `avatar/AvatarRig.java`, `avatar/PinWarpStrip.java`,
`avatar/DangleSim.java`, `avatar/PuppetPreviewView.java`, `avatar/FabrikSolver.java`.

---

## FABLE (Claude) — dynamic lane
status: ACTIVE
since: 2026-07-06 ~10:30 (Fable-day: A2 tracking core [dep-free driver bus + FABRIK hookup],
       then masking family [CompositingSpec / chroma key / track matte]. Commit per slice;
       will re-read this board before each slice + before any device batch.)
files: avatar/AvatarStudioActivity.java, avatar/PuppetPreviewView.java,
       NEW avatar/TrackingFrame|TrackingSource|TrackingParamPipeline|TrackingDriverBus|
       SyntheticTrackingSource.java, NEW tools/jvm-harness/TrackingCoreTest.java.
       NOT touching strings.xml (chip labels literal) to stay clear of opencode's §A/§C sweep.
       LATER SLICES (will re-claim here first): model/Clip.java + overlay-item model,
       export/CompositeExportOverlay|BlendModeGlEffect|PipFrameOverlay|ExportManager (standing
       locks), compositor/LayerPreviewController (standing lock). (Plus standing locks.)
NOTE (build infra): watcher + opencode gradle running CONCURRENTLY corrupts the
incremental resource merge (missing .flat → 100 "class R" errors; fix = delete
app/build/intermediates + retrigger). While the watcher runs, opencode must NOT
invoke gradle — save and read build.log instead.

## OPENCODE — dynamic lane
status: ACTIVE (code-only, no device needed)
since: 2026-07-06 ~10:00 (self-refilling loop: released device, doing pure code work)
files: FaditorEditorActivity.java, ChatAssistantActivity.java, CaptionStyle.java, tasks/LANES.md, tasks/Opencode-work.md
