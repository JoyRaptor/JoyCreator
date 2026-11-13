# BOT-3: Joybot you can trust: never claims what it didn't do, never loses work

**Milestone:** M1  ·  **State:** 🔨  ·  **Owner:** Joybot lane (Claude Opus 5.5, 11-13)  ·  **Checked:** 2026-11-13

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
- [x] **Honest by measurement** (11-13, audit BOT-A2): `joybot/HonestClaim` keeps a ledger of the request's changing
      steps (worked + changed the work, or failed; a retry that works clears the failure; reads are left out). Mixed
      result + a reply claiming all of it: the reply gets "Not all of that happened: “rename clip” didn't work (why). The
      other change did go through." The "nothing changed" check now also knows claims like "is called Intro now". The
      wider claim words are used only after a failure, never for the "make the change now" nudge (an honest description
      such as "the clip is called Beach" must not be pushed into a change). Bench 55/55 with `studio-rename-fails` and
      `studio-partial-fail` (both FAIL on the old code: positive control); `IdeaEngineTest` 10 honesty checks. Not on a
      phone with a live model. Left: touched-id comparison per claim (which item the reply names) stays a later step.
- [ ] **Live benchmark on free models** (`run-joybot-bench.sh --live`): needs an OpenRouter key on the PC
      (owner) or ~$10 credit for four models. Publish the table in `tasks/joybot/`.
- [ ] **Resume for VisLab and JoyBrush jobs**: checkpoint by conversation, not only project.
- [ ] **Edits against a revision**: long jobs commit only if the document hasn't moved, else a clear conflict
      (audit recommendation 2: document id + revision + operation id). Start with Studio.
- [~] **Model-aware size budget**: built 11-04, harness-tested (`bash tools/jvm-harness/run-promptbudget.sh`, 50 pass),
      not compiled by Gradle, not tried on the phone with a live model. `PromptBudget` gives a model with a window under
      64k tokens (or an unknown `:free` / `openrouter/free`, assumed 32k) a tool and project-summary budget: the tools of
      the families the request named are never dropped; the older core tools are shortened, then dropped, least relevant
      first; one line tells the model what was left out. Big or unknown paid models get exactly the old request.
      Left: read the `request size` log on the Note 9 with a free model; the window comes from OpenRouter's list.
- [ ] **Director review for VisLab and JoyBrush** changes (today the Review card lists Studio changes only).

## Specs, handoffs and evidence
- [Audit](../audits/2026-10-18/joybot.md) (recommendations 1–6), [fixes](../audits/2026-10-18/joybot_fixes.md),
  [benchmark](../joybot/BENCHMARK_2026-10-24.md), `tools/jvm-harness/joybot-bench/README.md`.

## Files and traps
- `ai/joybot/JoybotAgent.java`, `JobFile.java`, `JoybotStorage.java`, `ai/actions/*`. Keep the bench green
  after every change (`bash tools/jvm-harness/run-joybot-bench.sh`). Live runs: free models only, never paid
  without the owner's yes; never print a key.
