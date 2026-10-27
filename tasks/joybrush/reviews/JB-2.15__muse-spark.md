# Adversarial review — JB-2.15 SaveQueue + JB-0.08b rework (R26, Lead-landed core + wiring)

- Reviewer: muse-spark (cross-reviewer; builder was the Lead — different family by construction; R26).
- Task status: 🟧 Built — core (`b920689b`: `core/io/SaveQueue.kt` + test) and wiring (`7bee36ad`: every save through the queue).
- Spec reviewed: `tasks/joybrush/specs/JB-2.15_autosave_and_crash_safety.md` (contract, decisions 1–10, tests 1–12 incl. test-12 greps, owner checks, Do-not, stop rule + cross-reviewer rulings).
- §5b checks: core diff is NEW `io/SaveQueue.kt` + NEW `SaveQueueTest.kt` (pure, no threads/Android — the Do-not holds); wiring edits the declared app-file slot (serialised per the board banner; held by `83b15104` until JB-1.21 landed, then applied). Suites run by me in a clean HEAD worktree: `SaveQueueTest` 13/13, full `:core:jvmTest` participation green, `:androidkit:test` 104/104 (incl. `JbArchive` 32 — the archive half untouched as specified). Test-12 greps run by me against the wired tree: `saveOwed` = 1 (comment only), `compareAndSet` = 0, `SaveQueue` = 2. The five owner device checks remain the owner's (Definition-of-done requires them; no adb here).
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## What the queue fixes (verified against my JB-0.08b findings — the primary shapes are gone)
- **0.08b-F1 (copy discarded during autosave): FIXED.** No gate; EXPLICIT never merges; queued behind in-flight with its own destination preserved through `perform`. The exact dropped-arguments code is gone.
- **0.08b-F2 (save-owed eaten by undo): FIXED including the converse.** No flag (comment says so); history cannot release (only raises its own IDLE debt); release is exclusively the stroke-ended/cancelled signal; cancel now releases (it never did). Level-triggered hold while in flight.
- **0.08b-F3 (copy bypasses R11): HALF-FIXED — the queue hold works, the second door doesn't (see Finding 1).**
- **0.08b-F4 (cross-thread race): BYPASSED at the view** (UI-owned `drawing` flag; engine `strokeLayer` off the save path) — safe by UI confinement, source race untouched. Accepted.

## Finding 1 (MAJOR): no re-check at snapshot time — pen-down between gate and GL execution reproduces MAJOR-3's shape
Proof: the gate reads `strokeInProgress` on the UI thread (`SaveQueue` holds while true), but `readContents` runs later on the GL thread with no second check — `beginSave` (wiring) contains zero reads of `strokeInProgress`, contrary to spec Decision 4's explicit "checked in BOTH places on purpose … the copy path … incapable of bypassing it". A stroke starting in that window yields a pre-stroke snapshot reported as success ("Copy saved"). Window is small (gate-to-`queueEvent`), consequence is exactly the finding this row exists to kill. Add the `perform`-side refusal the spec orders.

## Finding 2 (MAJOR): the watchdog breaks single-flight and misattributes failures
Proof: on timeout the watchdog answers the CURRENT save as failed and clears `inFlight`, starting the next save — while the orphaned snapshot/`fileIo` write is still pending: two snapshots in flight (violates the queue's own one-readback invariant and spec Decision 7). Worse, `activeSave` is overwritten, so the orphan's `onSnapshotFailed` invokes the NEW save's answer with the OLD error. Reachable on every slow snapshot past the timeout. Fix: generation-guard the watchdog (ignore orphans) or cancel-before-advance.

## Finding 3 (MAJOR): `onSnapshotFailed` strands the queue behind a paused GL thread
Proof: unconditional `pauseViewNow()` instead of pause-if-drained. With entries waiting, the next snapshot defers until resume while the queue stays busy until the watchdog — a recoverable GPU-readback failure cascades into a ~15 s wedged queue. Pause only when nothing is waiting.

## Finding 4 (MAJOR): load completion wipes concurrent debt
Proof: `onDone` unconditionally `changes = 0` + removes idle callbacks after async GL work. A stroke landing between `showContents` and `onDone` is zeroed and will not autosave until the NEXT change — draw once right after open, force-stop, lose it. Snapshot the counter (or re-check) instead of clearing it.

## Finding 5 (MINOR): sync-throw path loses the request with no words and stalls the remainder
Proof: entry removed before `start`; `catch` rethrows with the entry already gone — never retried, never reported, remainder stuck until an external `drain`. Unreachable from the current wiring (no sync throw, `fileIo` never rejects) but contract-breaking for the next caller, and spec Decisions 5/6 + Tests 8/9 require report-and-continue. Remove-then-try, or catch-and-report-then-advance.

## Spec-vs-code deviations (for the Lead — triage rule 3, status unchanged by me)
The implementation knowingly departs from the written contract in five places; three are improvements the spec should absorb, two are spec promises with no counterpart:
- **Generic `SaveQueue<D>` + Activity-owned `SaveDest(Uri)` instead of core `SaveDestination(Uri-String)`.** The spec's own cross-reviewer note proves `android.net.Uri` cannot compile in `commonMain` — the generic is the RIGHT design (and carries the Uri-typed, not String). The spec contract text (plus Tests 8/9's throw-shape) should be updated to the generic, not the reverse.
- **`request()` returns the merged id, not 0; `pending` excludes in-flight; no `dropped` field.** Behaviour conforms (coalescing, ordering, never-drop pinned); the contract's observable spellings (`0`, "not yet performed", `dropped==0`) do not. Update the text to the pinned behaviour.
- **`strokeFinished` is level-triggered (any `drain` after flag-false releases), not exclusive.** Benign and more robust than the "NOTHING ELSE" wording; tests pin the safe half. Reword Decision 3 to the level semantics.
- **Decision 6 (failed request retried by next `drain`, two attempts counted) has NO counterpart** — landed doc explicitly assigns retry to the screen ("a queue that retried would be a battery bug"), only Working re-arms via idle, Copy never retries, no retry test exists. Either restore queue-held retry or rule the screen-owned design (and write Tests 8/9/10/12 against what was built — they currently assert unbuilt shapes).
- **Failure words live in the Activity, never cross the queue** (queue ignores the problem value). Delivery works end-to-end today, but the queue half of Decision 5 is unimplemented and untested — same ruling needed.
- Test-12 mechanical note: `saveOwed` grep returns 1 (the "deliberately no saveOwed" comment) — substance passes, literal paste fails. Reword to exclude the comment or accept 1-with-citation.

## Explicitly still open (already owned elsewhere — not re-filed)
- 0.08b-F5 (`onDestroy`/leak/release), F6 (metadata churn — spec Decision 9 explicitly out-of-scope, referred with scheduling recommendation), F7 (paper/name flash): all confirmed still present, all previously referred. No change.

## Recommendation
Findings 1–4 are real work-loss/wedge paths in Lead-landed code — fix 1 (the spec-ordered second door), 2–4 (watchdog/stranding/wipe) before the owner checks, since owner check 2–4 (mid-stroke Home, undo-mid-stroke, pen-down copy) walk exactly these windows. Finding 5 + the spec-deviation list go to the Lead (several deviations are improvements the spec should absorb, not code to revert). The queue's core serialisation — the thing my three MAJORS were really about — is sound and pinned (13/13).
