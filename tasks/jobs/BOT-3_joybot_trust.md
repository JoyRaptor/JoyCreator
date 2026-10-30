# BOT-3: Joybot you can trust: never claims what it didn't do, never loses work

**Milestone:** M1  ·  **State:** ⬜  ·  **Owner:** open (Joybot lane; frontier work)  ·  **Checked:** 2026-10-28 against `fbfa50ce4`

## Why it matters (for the user)
With free AI models, the weak point is honesty and safety: saying "done" when nothing changed, or a long job
colliding with your own edits. The 12 audit defects are fixed; these are the deeper guarantees the audit
recommended, plus measuring how well free models really do.

## Done when
- [ ] Joybot's "done" is checked against what actually changed, not against words in the reply.
- [ ] A job killed mid-way in VisLab or JoyBrush can carry on like a Studio job, without repeating an edit.
- [ ] The free-model success rate is measured and published (per model), not guessed.

## Where it stands (verified 10-28)
Audit J1–J12 fixed (b6248fd69) plus 4 phone finds (c473b0171, 9304277c2). Honesty is keyword-based
(`claimsAChange`): "The first clip is called Intro now" after a failed rename slips through (bench 10-24).
Job checkpoints (`JobFile`) only exist for Studio projects (`if (ask.projectId == null) return`), so VisLab
and JoyBrush requests are not resumable. The bench runs 53 tasks with a fake model; live free models never run
(no key on the PC). Per-request size ~19,000 characters.

## What is left
- [ ] **Honest by measurement**: compare the reply's claim with the request's real changes (write count,
      touched ids, app-wide changes) and say what really happened. Audit recommendation 3.
- [ ] **Live benchmark on free models** (`run-joybot-bench.sh --live`): needs an OpenRouter key on the PC
      (owner) or ~$10 credit for four models. Publish the table in `tasks/joybot/`.
- [ ] **Resume for VisLab and JoyBrush jobs**: checkpoint by conversation, not only project.
- [ ] **Edits against a revision**: long jobs commit only if the document hasn't moved, else a clear conflict
      (audit recommendation 2: document id + revision + operation id). Start with Studio.
- [ ] **Model-aware size budget**: shrink what is sent for small free models; trim the tool list further.
- [ ] **Director review for VisLab and JoyBrush** changes (today the Review card lists Studio changes only).

## Specs, handoffs and evidence
- [Audit](../audits/2026-10-18/joybot.md) (recommendations 1–6), [fixes](../audits/2026-10-18/joybot_fixes.md),
  [benchmark](../joybot/BENCHMARK_2026-10-24.md), `tools/jvm-harness/joybot-bench/README.md`.

## Files and traps
- `ai/joybot/JoybotAgent.java`, `JobFile.java`, `JoybotStorage.java`, `ai/actions/*`. Keep the bench green
  after every change (`bash tools/jvm-harness/run-joybot-bench.sh`). Live runs: free models only, never paid
  without the owner's yes; never print a key.
