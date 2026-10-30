# Developer & Agent Guide: Best Practices for Workflow Orchestration and Task Management

## Workflow Orchestration

### 1. Plan Node Default
- Enter plan mode for ANY non-trivial task (3+ steps or architectural decisions)
- If something goes sideways, STOP and re-plan immediately - don't keep pushing
- Use plan mode for verification steps, not just building
- Write detailed specs upfront to reduce ambiguity

### 2. Subagent Strategy
- Use subagents liberally to keep main context window clean
- Offload research, exploration, and parallel analysis to subagents
- For complex problems, throw more compute at it via subagents
- One tack per subagent for focused execution

### 3. Self-Improvement Loop
- After ANY correction from the user: update `tasks/lessons.md` with the pattern
-Write rules for yourself that prevent the same mistake
- Ruthlessly iterate on these lessons until mistake rate drops
- Review lessons at session start for relevant project

### 4. Verification Before Done
- Never mark a task complete without proving it works
- Diff behavior between main and your changes when relevant
- Ask yourself: "Would a staff engineer approve this?"
- Run tests, check logs, demonstrate correctness
  
### 5. Demand Elegance (Balanced)
- For non-trivial changes: pause and ask "is there a more elegant way?"
- If a fix feels hacky: "Knowing everything I know now, implement the elegant solution"
- Skip this for simple, obvious fixes - don't over-engineer
- Challenge your own work before presenting it

#### 6. Autonomous Bug Fixing
- When given a bug report: just fix it. Don't ask for hand-holding
- Point at logs, errors, failing tests - then resolve them
- Zero context switching required from the user
- Go fix failing CI tests without being told how

## Source of truth (project lead, 2026-10-27)
- **`tasks/ROADMAP.md` is the Joy Creator source of truth.** Read it first; it is enough to start.
  Each job has a card in `tasks/jobs/`; specs and handoffs hang off the card.
- How to work here without breaking other sessions: `tasks/AGENT_START.md`.
- Who is working on what right now, locks and messages across harnesses: `tasks/BOARD.md`
  (replaces `tasks/LANES.md`).
- Wings with their own roadmaps: JoyBrush `tasks/joybrush/ROADMAP.md`, VisLab `tasks/VISLAB_ROADMAP.md`.
- `tasks/todo.md`, `tasks/LANES.md`, `tasks/road_map.md` and dated handoffs are history, not a queue.

## Task Management
1. **Plan First**: Write your plan into your job card in `tasks/jobs/` (a new job gets a card from `tasks/jobs/_TEMPLATE.md` and a roadmap row)
2. **Verify Plan**: Check in before starting implementation
3. **Track Progress**: Tick the card as you go; update the roadmap row when the job's state changes
4. **Explain Changes**: High-level summary at each step
5. **Document Results**: Update `tasks/STATE.md` (no STATE row means not done), then the card and the roadmap row
6. **Capture Lessons**: Update `tasks/lessons.md` after corrections

## Core Principles
- **Simplicity First**: Make every change as simple as possible. Impact minimal code.
- **No Laziness**: Find root causes. No temporary fixes. Senior developer standards.
- **Minimal Impact**: Changes should only touch what's necessary. Avoid introducing bugs.

## JoyBrush roadmap and Git hygiene (owner, 2026-10-26)

- `tasks/STATE.md` is the required cross-wing major-feature catalogue for marketing,
  Joybot/help mapping and engine reuse. Read it before designing a feature. After
  a noteworthy capability is built or materially changes, update its user benefit,
  entry point, actual branch/availability, proof/limits, reusable engine/source and
  help link (or explicitly pending). Keep minutiae in handoffs. Agent phone checks
  or compilation do not silently become owner LIVE or promotional approval.
- `tasks/joybrush/ROADMAP.md` is the mandatory source for JoyBrush's broad
  milestones, current ownership, dependencies and remaining acceptance gates
  (the rest of Joy Creator uses `tasks/ROADMAP.md`, which links it).
  Read it at session start. Update the affected milestone after every achievement
  on it, including a completed spec, integration or verification gate. This is part
  of completing the work, not optional documentation or a secondary board.
- Keep granular substeps, logs and ongoing lead context in dated handoffs/reviews.
  The roadmap summarizes their effect and links evidence; do not paste every test
  assertion into it. Unrun source and isolated proofs must not become feature-done.
- Use one canonical roadmap on the coordinator branch. Specialists update their
  isolated branch's affected row with their achievement and send the small docs
  patch to the coordinator. Reconcile against the current row; never replace the
  whole board from a stale checkout. The coordinator publishes the merged update.
- After changing live roadmap/start/STATE links, run
  `python -B tools/check_joybrush_docs.py`. This checks local targets only;
  source/evidence review still establishes feature-state truth.
- Before edits, inspect checkout/branch/status and fetch remote state when online.
  Preserve dirty/staged work and use an isolated branch. After a coherent reviewed
  checkpoint, commit explicit owned paths, push its branch and verify local HEAD
  equals the remote branch SHA. Report failures or offline/unpushed work honestly.
- Do not force-push shared branches, auto-pull stale primary folders, delete old
  worktrees/evidence or reset local changes to make the tree look tidy. Inventory
  unique commits and evidence before cleanup. Never commit local caches, generated
  APK/build output, secrets or owner media. Keep necessary raw evidence in its
  designated location and durable receipts/paths in the handoff.
- Before handoff, state exact source/base, local/remote synchronization, owned dirty
  paths, current jobs/locks and next action. Historical plans must be clearly marked
  superseded and point to the current roadmap instead of advertising stale queues.

---
## Phone rule (owner, 2026-10-19)
- **Never launch the app with `adb shell monkey`**: it turns the owner's screen-rotation lock OFF on the phone (see tasks/DEVICE_CONTROL_RUNBOOK.md). Launch with `am start -n <activity>` (resolve it with `cmd package resolve-activity --brief`). Never change the phone's rotation settings.
