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

**Standing (permanent) Fable locks — never touch regardless of this board:**
`export/ExportManager.java`, `export/BlendModeGlEffect.java` (when it appears),
`project/ProjectStorage.java`, `compositor/MasterPlaybackEngine.java`,
`compositor/OverlayVideoPreviewView.java`, `compositor/DecoderBudgetProbeActivity.java`,
`avatar/PuppetPoseResolver.java`, `avatar/AvatarRig.java`.

---

## FABLE (Claude) — dynamic lane
status: IDLE
since: 2026-07-06 ~09:40 (z-unification landed a4fbeba — M-EXPORT-2 fully closed.
       Tree clean. Ready for the Fable-day Tier-1 task: masking / A2 tracking / ping-pong unpark.)
files: (none. Standing locks: avatar/PinWarpStrip.java, avatar/DangleSim.java,
       export/BlendModeGlEffect.java, export/PipFrameOverlay.java)
NOTE (build infra): watcher + opencode gradle running CONCURRENTLY corrupts the
incremental resource merge (missing .flat → 100 "class R" errors; fix = delete
app/build/intermediates + retrigger). While the watcher runs, opencode must NOT
invoke gradle — save and read build.log instead.

## OPENCODE — dynamic lane
status: IDLE
since: 2026-07-06 (AI copy btn + ticker nav deployed; awaiting TASK 8 results)
files: (none)
