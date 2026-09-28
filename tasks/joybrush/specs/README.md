# Joy Brush specs — how the build is shared between Claude and cheaper models

**Why this exists (owner, 2026-09-28):** JoyRaptor is short of money. When Claude usage runs out he
keeps building in the opencode harness with the best free models of the day (GLM 5.3, DeepSeek 4.1,
MuseSpark 1.3, Xiaomi models …). So Joy Brush is built from **spec sheets that a capable but
non-frontier model can execute reliably**, and Claude spends its budget on the parts that must be
right.

---

## 1. Tiers — every spec says which one it needs

| Tag | Who | What kind of work |
|---|---|---|
| **T1** | Claude (frontier) | Architecture and contracts, the hard maths (brush engine stages, GPU tile engine, wet paint), anything that spans modules, integration into the app build, and **reviewing** T2 work before it merges. |
| **T2** | Any strong coding model, no vision needed | Pure logic against a contract that already exists, with the tests written into the spec: parsers, file formats, geometry, algorithms. |
| **T2-V** | A model with **strong vision and design sense** | UI that must match Joy Creator's look. Needs to read screenshots and the design records and compare its own screenshots against them. Models without vision must not take these. |
| **T3** | JoyRaptor, on the phone | The device check that ends a phase. Nobody else can do it. |

If a model is unsure whether it is the right tier for a spec, it is not.

## 2. The runway rule
Claude keeps **at least one phase of ready-to-run specs ahead of the builders** ("the runway"), so
free models never sit idle and never have to invent a design. Specs further out are only outlines in
`INDEX.md` — contracts change as the engine is built, and a detailed spec written too early goes
stale and misleads (this repo's `tasks/` folder is the evidence).

## 3. What makes a spec executable by a T2 model
Every spec in this folder follows `SPEC_TEMPLATE.md`, and must have:
1. **One owner area.** The exact files and folders it may create or edit. Nothing else is touched.
2. **The contract verbatim.** Every type, function signature and file format the work must match,
   pasted in — never "see the other file".
3. **Every decision already made.** No "choose a good approach". If there are two ways, the spec
   says which one and why.
4. **Tests written in advance** (or precise test cases), plus the exact command that runs them.
5. **A "do not" list** — the traps a model is likely to fall into.
6. **Definition of done** — tests green, command output pasted into the report, files listed.
7. **A stop rule:** if anything is ambiguous or a contract seems wrong, STOP, write the question in
   the spec's *Questions* section, and end. Guessing is how data gets lost in this repo.

## 4. Workflow for a builder (any model)
1. Read `START_HERE.md` rules 1–10, then this file, then your spec. Nothing else is required.
2. Claim the spec in `tasks/LANES.md` (spec id + files) before editing.
3. Build exactly what the spec says. Run its test command. Paste the output in your report.
4. Commit only the spec's files, with the spec id in the message (`JB-0.03: …`). Push.
5. Mark the spec **Built — awaiting T1 review** in `INDEX.md`. Never mark it Proven.
6. T1 reviews; T3 (the owner) proves on the phone where the spec requires it.

## 5. Where things live
- Engine code: `joybrush/` (standalone Gradle build; `./gradlew -p joybrush :core:jvmTest`).
- Design direction: `tasks/joybrush/JOYBRUSH_BLUEPRINT.md`.
- Owner rulings: `tasks/joybrush/OWNER_CONSTRAINTS.md` — a spec never contradicts these.
- Visual language: `tasks/joybrush/design/`.
- Research (background only, not instructions): `tasks/joybrush/research/`.
