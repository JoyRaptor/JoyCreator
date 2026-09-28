# JOY BRUSH — ORCHESTRATOR LOG

Handover file. If the orchestrator's context fills, read this first: it says what is in flight, what
landed, and what is still open. **The orchestrator alone edits `ROADMAP.md`, commits and pushes.**
Subagents never commit, push or touch the board — they build, run the spec's test command, and report.

Promoted 2026-09-28 (space bunny agent #1, from JB-0.02).

---

## Ground rules I am holding myself to

- **Never** resolve a merge conflict. If `git pull` or `git push` refuses, stop and report the message.
- **Never** stash, commit or touch the 6 uncommitted app files, or another agent's untracked work.
  Those files are the owner's and other agents', not mine.
- **Never** run gradle except `./gradlew -p joybrush :core:jvmTest`, which builds only the standalone
  `joybrush/` project. Anything under `app/` or `joybrush-android/` is proven by the watcher's
  `build.log`, never by running gradle (START_HERE.md rule 2).
- **Never** `adb uninstall` (START_HERE.md rule 3).
- At most 5 subagents at once, and only tasks whose owner areas share no files with anything in flight.
- Commit with explicit paths. Never `git add -A`, never `git commit -a` — other agents have
  uncommitted work in this tree and a blanket add would swallow it.

## Question-handling policy

- **Low-risk** (naming, a helper's signature, a message string): I rule, marked
  `PROVISIONAL — Claude to confirm` in the spec, and say so in the log.
- **Contracts, file formats, app build files, anything touching the phone**: leave `⛔ Blocked` with
  the question in the spec's Questions section. That is Claude's, not mine.

---

## Landed

| Task | What | Commit | Who |
|---|---|---|---|
| JB-0.02 | Document model, boards/layers/cels, `document.json` | `2175cf1c` | space bunny agent #1 |
| JB-0.03 | Brush preset format + dynamics evaluator | `5295eacf` | (agent, unnumbered at the time) |
| JB-0.04 | Stroke recording codec | `f8366435` | space bunny agent #3 |
| JB-1.03 | Procedural tileable cloud grain texture | `3fea7770` | space bunny agent #4 |
| JB-0.01, JB-0.07, JB-1.01, JB-1.02 | Engine core, GPU tile engine, tip + grain shader maths | earlier | Claude |

`joybrush/core/build.gradle.kts` (serialization plugin + `kotlinx-serialization-json`) was needed by
both JB-0.02 and JB-0.03 and was committed once, by JB-0.03's agent. Both tasks are satisfied by it.
Nothing is owed there.

**JB-0.02 note for whoever picks up JB-0.08:** `DocJson.encode` sorts `frameCel` keys, so a re-save of
an unchanged document is byte-identical. Do not "simplify" that away — it is what makes a `.joybrush`
diff meaningful.

**JB-1.03 note:** its `WriteGrainAssets` test **overwrites** `joybrush/assets/grain/*.png` on every
run. That is by design, but it means anyone who hand-touches those PNGs will silently lose the edit,
and it makes the working tree dirty after any test run. Worth knowing before a review "fixes" a PNG.

---

## Turn 2 (promoted to orchestrator) — what happened

Dispatched three T2 tasks in parallel. **Two of my three subagents could not run gradle** — their
shells were denied by their own permission config, not by anything I did. So *I* ran
`./gradlew -p joybrush :core:jvmTest` as the authoritative check, exactly as my instructions say.
This is not a small detail: **subagents in this harness cannot be relied on to produce a green build.**
Always run the command yourself before landing anything.

| Task | Result | Status set |
|---|---|---|
| JB-5.10 | Landed. 40 tests green. | 🟧 Built |
| JB-1.20 | Code landed, **unverified in a browser** | ⛔ Blocked |
| JB-2.10 | **Two dispatch attempts produced nothing** | back to 🟦 Ready |

### Failures I hit, so the next orchestrator does not repeat them

1. **A subagent's code had never been compiled.** First `:core:jvmTest` on JB-5.10 failed with
   `VectorEraser.kt:362:30 Function invocation 'lines()' expected` — a function referenced a name not
   in scope. Sent it back; it then passed a KDoc link `[lines]` as the false lead. **Lesson: give
   builders a conservative-Kotlin list** (no `val` in `when` branches, no `arr[i++]` in expressions, no
   reach-outs) and tell them they cannot compile, so they write boring code.
2. **A wrong formula that was documented as if it were right.** `segmentSegmentDistance` returned
   `min(four endpoint distances)`, which is exact only for *disjoint* segments, and its KDoc asserted
   the opposite in a confident sentence. The KDoc *was* the bug. **Lesson: a confident comment is not
   evidence. Make the test derive its expectation from the geometry rather than a hand-picked number —
   the builder reconciled one "by hand" and wrote a wrong expectation that then had to be undone.**
3. **A subagent invented a file format instead of reading the landed one.** JB-1.20 guessed
   `brush.json` field names when `BrushPreset.kt` already existed, committed and Built. Its guess put
   flat numbers where the contract wants `Param` objects and omitted the required `id`/`name` — a file
   the phone would have thrown on. **Lesson: when a spec says "the JB-0.03 format", put the path to
   the real file in the subagent's brief, and treat "I had to guess a format" as a blocked task, not
   a task with an open question.**
4. **Two of three subagents could not run the test command.** Budget for running it yourself.
5. **One task (JB-2.10) returned an empty report twice with CoderAgent and wrote no files.** The spec
   is fine (8,162 bytes, no BOM, CRLF like its siblings) — it is agent flakiness on that task. If it
   fails a third time, build it on the main thread or change agent type.

### Verification I could add that the subagents could not do
`node --check` on the lab's extracted `<script>` block: **passes** (47,697 bytes). That eliminates
syntax as a cause. It does **not** compile the GLSL, does not open a browser, and does not draw a
stroke. JB-1.20 is therefore unproven in the only way the spec cares about, which is why it is Blocked
rather than Built.

---

## In flight

| Task | Owner area | State |
|---|---|---|
| JB-0.05 | new `joybrush-android/`, app build files | **space bunny #5**, not mine. Needs 0.01, 0.07 (Built). |
| JB-1.03 | `joybrush/core/.../grain/` | landed `3fea7770` |
| JB-2.10 | new `joybrush/core/.../shape/` | **lane free, needs a third attempt** |
| JB-1.20 | new `tools/brushlab/` | code landed, waiting on a browser + Claude |

### Why D.01 is not running even though it is 🟦 Ready
D.01 needs **JB-0.05** (`Depends on: JB-0.05 — the joybrush-android module must exist`). JB-0.05 is
still 🟦 Ready and in bunny #5's hands, so D.01's Needs are not met and it must wait. The
ROADMAP §2 step-3 rule is "every task in Needs is at 🟧 Built or better" — D.01 fails it today.
This is the rule doing its job, not an oversight.

### Owner-area overlap map (check before dispatching more)
```
JB-1.20  tools/brushlab/                     (new, disjoint)
JB-2.10  joybrush/core/.../shape/            (new, disjoint)
JB-5.10  joybrush/core/.../vector/           (LANDED, disjoint)
JB-0.05  joybrush-android/ + settings.gradle.kts, build.gradle.kts,
         gradle/libs.versions.toml, app/build.gradle.kts, AndroidManifest.xml
D.01     joybrush-android/src/main/res/values/jb_tokens.xml,
         joybrush-android/.../JbColors.kt, tools/check_joybrush_tokens.py
```
JB-2.10 lands in the same Gradle module as JB-5.10 did but a different subfolder, so they share no
file. They do share one build directory, so a JB-2.10 build will also compile `vector/` and `grain/`.
That has been fine in practice, but a failure outside the dispatched task's own folder is a
**shared-tree conflict to report, never to fix**.

### Not mine — do not commit
- `tasks/joybrush/reviews/*.md` — MuseSpark cross-reviews, another agent's untracked work. Upstream
  has since added `tasks/joybrush/reviews/README.md` (in `fed130e3`), so the directory is becoming a
  tracked convention, but the 8 review files themselves are still untracked and are not mine.
- The 6 app files (`ProjectStorage.java`, `FaditorEditorActivity.java`, `LobbyFragment.java`,
  `strings_studio_polish.xml`, `INBOX.md`, `LEDGER.md`) — the owner's. Three are *staged*, so a bare
  `git commit` would swallow them. Always commit with explicit paths.

---

## Review triage (ROADMAP §5b), pass 1 — muse-spark, 2026-09-28

**Only one reviewer's pass is on disk.** All 8 files are `__muse-spark.md`, so §5b rule 2 (the same
issue found independently by *both* reviewers → treat as reproduced) **cannot fire for anything yet**.
Rule 2 becomes available when the second family files its pass. I re-checked the two most consequential
findings against the code myself rather than trusting the prose.

Severity mapped from the reviewer's own scale (High/Medium/Low → BLOCKER/MAJOR/MINOR); the reviewer
predates §5b and used its own labels.

### 🔴 The one BLOCKER — and I cannot fix it without a collision
**JB-0.03 F1: `size.base = +Inf` (JSON `1e999`) passes validation, then the stroke dies after one dab.**
`BrushValidate.kt:28` uses `!(p.size.base > 0f)`, and `+Inf > 0f` is true. `Dynamics.eval` is
deliberately unclamped, so size evaluates to `+Inf`; `DabPlacer` places the first dab then sets
`untilNext = Inf` forever. Net: a brush with `"base": 1e999` draws one dot and silently swallows the
rest of the stroke. The suite already documents the adjacent case (NaN hardness passes validation).
**Why I am not fixing it:** the fix belongs in `core/brush/BrushValidate.kt` + `BrushTest.kt`, which is
*exactly* the owner area of **JB-0.03b, which is 🟦 Ready and unscheduled**. Dispatching a fix now
would put two agents in one owner area — the exact failure that started this whole session.
**This must be folded into JB-0.03b before it is dispatched.** Cheapest correct ruling: `size.base`
must be finite (one predicate, one test).

### 🔴 Systemic contract issue, two independent tasks, one root cause
**Unknown enum values break the forward-compat promise in both JB-0.02 and JB-0.03.**
- JB-0.02 F1 (MAJOR): `BoardKind` / `LayerKind` / `BlendMode` are plain kotlinx enums. `ignoreUnknownKeys`
  covers unknown *keys*; kotlinx **throws** on an unrecognised enum constant, so a v2 document with a
  new board kind cannot be opened at all, and never reaches `validate`'s friendly "newer Joy Brush"
  message.
- JB-0.03 F3 (MAJOR): identical defect on `BrushInput` — one brush unopenable instead of one document.

The reviewer reached this independently in both files. **This is the single most important thing for
Claude.** The spec promises forward compat, so the code is wrong rather than the spec, but *what an
unknown kind becomes* (fallback? null? refuse?) is a product decision. I have logged it as a Lead
question on both specs and changed no code. Note JB-0.03 got `engine`/`accumulate`/`blend` right by
using string-typed words; the enums did not.

### ✅ Closed by Claude's incoming commit — no action from me
**JB-0.07 F2 (`radiusOf` evaluated twice per dab) is already fixed** in `c563d1f5`: the three
per-sample lambdas are replaced by a single `DabLook` asked "exactly once per dab, with the distance
travelled and the dab's index". I read the diff to confirm. The commit message even credits the
JB-0.03 builder for catching it. This corroborates JB-0.03 Q6 and closes the loop.

### 🔴 Still open, verified by me against origin — not fixed by that same commit
**JB-0.07 F3: `DabPlacer.lerp` snaps azimuth and barrel.** On `origin/joy-creator` today:
`azimuth = b.azimuth`, `barrel = b.barrel`, while x/y/time/pressure/tilt are interpolated and tilt is
NaN-guarded. On a fast stroke with a leaning pen, `lean`/`attack` step instead of sweep, and the
tilt-aimed grain plane inherits it. One-line fix (`Angles.lerp`, already wrap-aware in
`StrokeSmoother`). **Not dispatched:** `DabPlacer.kt` is Claude's T1 file and JB-1.05a/JB-1.05b will
touch the dab path; three agents in one file is how JB-1.04 detonates. Sequence it after those.

### 🟡 Referred to the Lead (contract / file format / phone — my standing instruction, not §5b rule 1)
| Where | Finding | Why Lead |
|---|---|---|
| JB-0.02 F1, JB-0.03 F3 | unknown enum values | file format + what an unknown value becomes |
| JB-0.03 F2 | no count limits on inputs-per-Param / points-per-curve | DoS policy; reachable via hot-reload and Phase-8 imports |
| JB-0.02 F2, JB-0.03 F5 | `version < 1` accepted | the spec's own rule 1 *literally permits* it, so this is a spec gap, not a code bug |
| JB-0.07 F1 | `GlPaintEngine.init()` never clears layers/undo/pools — after a GL context loss the user sees black blocks with a lying undo history | phone-facing, T1, and androidkit has no spec file of its own |
| JB-0.01 F1 | `StrokeSmoother` accepts `screenPerDoc = 0` → Inf/NaN replay coordinates | T1 core contract |
| JB-0.01 F2, JB-1.01 F1 | `Curve`/tip accept non-finite → NaN paint | same ruling as JB-0.03 F1 (finite-or-reject) |
| JB-1.02 F1 | `jb_grainLevel` NaN-poisons the plain-finger case | must be guarded before JB-1.05c wires grain |
| JB-0.07 F3 | azimuth/barrel snapped (above) | T1 file, sequencing |

### ✅ Ruled by me — low-risk, PROVISIONAL, Claude to confirm
- **JB-0.02 F3:** an empty-string id passes validation. → **reject blank ids**, matching JB-0.03's
  blank-brush-id rule. Does not contradict the spec (which never mentions id emptiness).
- **JB-0.02 F4 / JB-1.03 F2 (Info):** `Cel.tiles` is a *list*, so tile order is not canonical.
  **Do not "fix" this by sorting** — order may one day carry meaning. Do not assume set semantics.

### ⚪ Recorded, no action (unspecified edge, both layers agree, or owner-ruled)
- JB-0.04 F1 (`ArrayList(count)` pre-sized from an untrusted count → OOM on the Note 9),
  F2 (trailing bytes ignored — a defensible forward-compat posture), F3 (the "bad UTF-8" catch is dead
  code because `decodeToString` substitutes U+FFFD rather than throwing — **do not leave a promise the
  code cannot keep**). All three are hardening for **JB-0.08a's** author, which now exists.
- JB-0.04 also flags `Tool` persisted by `ordinal`: **do not reorder that enum** or old stroke files
  break silently.
- JB-0.07 F4 (negative/NaN radius silently dropped) — same ruling as JB-0.03 F1.
- JB-0.07 F5 (finger dead forever after the first pen event) — **owner-ruled and documented**. No
  change. The reviewer notes the strand risk for a dead-pen-battery user; that is the owner's call.
- JB-0.01 F3 (`OneEuroFilter` has zero callers and zero tests), F4 (`DirectionTracker` returns NaN
  instead of falling back on Inf tilt + NaN azimuth — ties to JB-1.02 F1).
- JB-1.01 F2 (`minPx ≤ 0` and out-of-range shape params silently become something else — CPU and GPU
  agree, so parity holds).
- JB-1.03 F1 (a flat-field input violates the "min = 0 AND max = 1" contract; reachable, degenerate
  only) — **the one clean fix candidate**, but `core/grain/` is bunny #4's lane and I will not
  dispatch into it while #4 may still be in there.

### 🟩 Eligible for 🟩 Reviewed (xr) — no BLOCKER or MAJOR open
Only **two** of the eight reviewed tasks qualify:
- **JB-0.04** — three MINOR findings, all deferred to JB-0.08a by the reviewer's own recommendation.
- **JB-1.03** — one MINOR (degenerate flat field) and one Info.

Everything else has an open MAJOR or BLOCKER, so it must stay 🟧 Built. **I have not moved any row to
🟩 Reviewed**, because doing so requires editing `ROADMAP.md`, and that is the one file currently in a
merge conflict I am not allowed to resolve.

---

## Turn 3 — LEAD_RULINGS R1–R8 read, JB-0.02b + JB-0.03b landed

`:core:jvmTest` → **BUILD SUCCESSFUL, 161 tests, 0 failures** (was 142 before this batch).

### Rulings closed by Claude's `061fd2b5`, so they are no longer my open questions
- **R1 (engine half)** — `DabPlacer` clamps every dab to finite values; test
  `anInfiniteBrushSizeCannotFreezeTheStroke`.
- **R2** — the azimuth/barrel interpolation I flagged as *still open* is fixed
  (`leanDirectionTakesTheShortWayRoundBetweenSamples`). My earlier check was correct at the time and
  is now superseded; do not re-flag it.
- **R4** — `Tool` ordinal freeze, with `ToolOrdinalFreezeTest`.
- **R3** — unknown enum values are a **version rule, not a code bug**: a new constant requires a
  version bump, and readers refuse a newer version with a clear message. No behaviour change. My
  JB-0.02 F1 and JB-0.03 F3 referrals are answered.

### The Infinity BLOCKER is closed at three independent layers — better than reported
The reviewer's proof assumed `1e999` decodes to `+Inf`. **With kotlinx's default strictness it does
not**: the parser refuses a non-finite literal outright (`Unexpected special floating-point value
Infinity … at path: $.size.base`). So the original "any stroke with this brush draws one dot" path is
narrower than the finding claimed. The builder added the sharp probe — **`1e40`**, a perfectly
well-formed JSON number that `Float.parseFloat` should saturate to `+Inf` — and it is **refused
too**. So: parser rejects the literal, parser rejects the saturating number, validation rejects a
`Param` holding `+Inf` by whatever route, and the engine clamps regardless. **Which layer is meant to
speak to the person is still the Lead's call** (logged as a question, no code changed).

### Two mechanical errors I fixed myself, because the subagents cannot compile
Both were flagged as risks by the subagent that wrote them; recording them so nobody re-"fixes" them:
1. **`BrushTest.kt:472`** — `}) })` with `}` and `)` transposed, closing the `preset { }` lambda
   before `it.copy(`. One syntax error, ~160 phantom "Unresolved reference" messages downstream.
2. **`ShippedBrushFilesTest.kt:13`** — the KDoc contained `` `joybrush/brushes/*/brush.json` ``.
   **Kotlin nests block comments and the lexer steps past the `/*`, so it never sees the overlapping
   `*/`** — the comment stayed open to EOF. Rewritten as `joybrush/brushes/<folder>/brush.json`.
   **Never put a glob containing `/*` inside a KDoc.**

### The cap rule was right; the test was wrong
`aFileCannotCarryUnlimitedCurve` failed with zero messages. Not a bug in `BrushValidate`: the fixture
built a 9-point curve and asserted the **point** cap (64) using the **input** cap's number. The
builder conflated the two caps. The DoS finding (JB-0.03 F2) **is** fixed. Tests now use 65 and
1000 points so the asserted count proves the fixture is real.

### 🔴 For the Lead: a maintenance trap the subagent named honestly
`BrushValidate`'s `RANGED_BASES` deny-list and `paramsOf` are a **hand-written enumeration** of the
eight `Param`s in `BrushPreset`. A `Param` added to `BrushPreset` and forgotten in `paramsOf` is
checked by **none** of rules 16–20 — which is exactly the `tip.hardness` NaN bug this task closed.
It is complete today, and no test can catch a future omission (enumerating a data class's `Param`
fields needs reflection, and the build forbids new dependencies). **The structural fix is a
`BrushPreset.allParams()` — which is `BrushPreset.kt`, outside JB-0.03b's owner area.** Logged as
JB-0.03b Question 6.

### 🔴 For the Lead: the guard test in JB-0.02b cannot enforce what it looks like it enforces
The subagent's own blunt answer, which I am passing on unchanged: appending `"HOLOGRAM"` to the
frozen name list makes the suite green with **no version bump**, because the harm only manifests
inside an *older* build, which nothing here can instantiate. What the test buys is conspicuousness,
not enforcement. The documentation half is **entirely unguarded** — deleting all 39 KDoc lines leaves
the suite green. Both are recorded as JB-0.02b Questions 1 and 2.

### Parallel dispatch: verdict
Two T2 tasks ran concurrently, owner areas disjoint (`core/doc` + `BrushPreset` comments vs
`core/brush` validation), and **neither agent touched a file that was not its own**, including when
each hit shared-tree build noise. `DocModel.kt` and `BrushPreset.kt` were verified **comments-only**
mechanically (`git diff` filtered to non-comment added lines: none), not taken on trust. Both
subagents volunteered weaknesses in their own work. **Parallel dispatch is safe when owner areas are
disjoint — but I am still the only verifier, so each batch lands before the next is trusted.**

## ROLLING ROSTER

The live picture. I alone edit the board, commit and push; subagents only build and report.

### 🟢 Landed and green (`:core:jvmTest` — 228 tests, 0 failures)
| Task | What | Who |
|---|---|---|
| JB-0.02b | new enum constant ⇒ version bump (R3) | subagent of openrouter/stealth/space-bunny-alpha |
| JB-0.03b | brush validation hardening + the Infinity BLOCKER (R1) | subagent of openrouter/stealth/space-bunny-alpha |
| JB-3.01 | animation model ops — 44 tests | subagent of openrouter/stealth/space-bunny-alpha |
| JB-5.02 | stroke picking in dense line work — 23 tests | subagent of openrouter/stealth/space-bunny-alpha |

### 🔵 In flight
_(none right now)_

### ⬜ Ready, not yet dispatched
| Task | Why not yet |
|---|---|
| JB-1.04 BrushDabber | 🟦 Ready, needs 0.03 ✅. Dispatch 1 returned empty, 0 files. Retry with a scaffolded brief. **Keystone of Phase 1** — unblocks 1.05a → 1.05b → 1.21. |
| JB-0.08a `.joybrush` archive | 🟦 Ready, needs 0.02 + 0.04 ✅. Dispatch 1 returned empty. Retry. Owns the one line of `androidkit/build.gradle.kts` this round. |
| JB-2.14a PNG writer | 🟦 Ready, no deps. **Held** so it does not race JB-0.08a for `androidkit/build.gradle.kts`. Send in the batch *after* 0.08a lands. |
| JB-2.06a flood fill · JB-2.13a region renderer · JB-3.06a GIF · JB-4.03a sprite packer · JB-8.03 MyPaint import | 🟦 Ready, deps met, all in fresh folders. Nothing blocking them. |

### 🔴 Blocked on another task
`JB-0.06` · `JB-0.08b` · `JB-1.05a` · `JB-1.05b` · `JB-1.21` · `JB-2.02` · `D.01` — all need **JB-0.05**, which is space bunny #5's lane, not mine.
`JB-2.14b` needs 2.13a + 2.14a + 0.08a.

### ⛔ Not dispatchable at any tier
| Task | Why |
|---|---|
| **JB-2.10** shape recognizer | **3 dispatches, 2 agent types, every one returned an empty report and wrote 0 files.** The spec is sound (136 lines, no odd encoding) — it is simply the heaviest maths in the T2 set: PCA on a 2×2 covariance, Kåsa algebraic circle fit, Ramer–Douglas–Peucker, and arc-length parametrisation, all derived from scratch with no reference, ~750 lines. **Do not run a 4th identical retry.** It needs one of: (a) split into 2.10a *recognise* / 2.10b *perfect*; (b) a brief that hands over the derivations; (c) a T1 attempt. That is a Lead decision — logged, not actioned. |

### 🧭 Sweep notes (updated 2026-09-28)
- **JB-5.02 is the first feature with a spec that named a *failure mode* rather than a function** ("distance-to-centreline is not enough, the stroke under the pen is usually not the nearest centreline"), and it shows: the half-width is in the score, ties are resolved by recency, and the agent found and fixed a `NaN` clock hole and an infinite-zoom slop. Read this spec's shape when writing the next one.
- **Two `AnimOps` test failures were the same mistake twice**: a test asserting a *generation counter* instead of the invariant. Fixed structurally (identify the added frame as "the one not there before"), not by nudging an index. Watch for this in every agent's tests.
- **Subagent reliability: 6 of 10 dispatches produced code.** Failures cluster by *task*, not by agent type. Treat an empty report as "nothing was written" and check the filesystem rather than waiting for a report.

## Open questions

### 🔴 For Claude — contract, and the only one that can lock a user out of their own file
**JB-0.02 Finding 1 (Medium), from the MuseSpark cross-review.** `DocJson` sets
`ignoreUnknownKeys = true` and promises "a document written by a later Joy Brush still opens here".
That is true for unknown *keys* but **false for unknown enum values**: `BoardKind`, `LayerKind` and
`BlendMode` are plain kotlinx enums, and kotlinx throws `SerializationException` on an unrecognised
enum constant (say a future `"kind": "VIDEO"`), which `decode` turns into `DocException`. So a v2
file using a new board kind never reaches `validate`'s friendly "newer Joy Brush" message — the user
simply cannot open it. The fix space (unknown-enum fallback, `coerceInputValues`, or explicit mapping)
is a contract decision and therefore **Claude's, not mine**. This must be settled before JB-0.08, or
the forward-compat promise in the spec is a lie. Note JB-0.03 got this right by using string-typed
words for `engine`/`accumulate`/`blend`; the document enums did not.

### 🟡 For Claude — JB-5.10 Question 1 (contract contradiction)
The spec's **Decision 2** mandates dropping surviving pieces under 0.5 doc px; **Decision 4** says
only "the complement is the survivor list". The builder implemented the filter for `PARTIAL` only
(literal reading), so `TO_INTERSECTION` can return a sub-0.5 px sliver. I did **not** rule, because it
is erase-mode semantics. Consequence for JB-5.11: **do not treat the sliver behaviour as settled.**

### ✅ Ruled by me — low-risk, PROVISIONAL, Claude to confirm
- **JB-0.02 Finding 2 (Low):** `version < 1` (0 or negative) decodes and validates clean, read with v1
  assumptions. → **ruling: reject `version < 1` with words**, same class as JB-0.03 Q4.
- **JB-0.02 Finding 3 (Low):** an empty-string id passes validation (uniqueness is checked,
  non-emptiness is not). → **ruling: reject blank ids**, matching JB-0.03's blank-brush-id rule.
- **JB-5.10 Q2:** keep `EraserPath` tolerant — an empty path means "the pen has not moved yet", which
  is a real state mid-stroke, so it must not throw. Mismatched `xs`/`ys` keep using the shared prefix.
- **JB-5.10 Q5:** a degenerate segment **does** consume a parameter step; a survivor resumes at the
  *second* parameter. (Answer: 2.6 in the spec's worked example. The test now derives this.)
- **JB-5.10 Q3:** collinear overlap counts as a crossing at **both** ends of the overlap, so a line
  lying along another trims to where the overlap starts and ends. Provisional.
- **JB-5.10 Q6:** `TO_INTERSECTION` is quadratic in line count. The bounding-box sweep prunes the
  common case; 50 long lines sharing one region is the untested worst case. Not blocking — a spatial
  grid is the fix if a real layer ever looks like that.
- **JB-0.02 Finding 4 (Info):** `Cel.tiles` is a **list**, so two documents with the same tiles in a
  different order encode to different bytes. **Do not "fix" this by sorting** — order may one day carry
  meaning. Just do not assume set semantics.

### Still outstanding, and nobody but a human can close it
- **JB-1.20's visual check.** Screenshots and a clean console, per its own DoD. I have no browser and
  no GLSL compiler. The GLSL has never been compiled by a driver and no frame has ever been rendered.
  It also cannot evaluate shaped `Param` curves, and `blend: "erase"` previews as `normal`.
- `D.01` is blocked on JB-0.05 (above), not on a question.

## Next up

`origin` moved 5 commits ahead (`3fea7770..8b0654a1`) while I was working: Claude Code Cloud landed 19
new specs (JB-0.06, JB-0.08a/b, JB-1.04, JB-1.05a/b, JB-1.21, JB-2.02, JB-2.06a, JB-2.13a, JB-2.14a/b,
JB-3.01, JB-3.06a, JB-4.03a, JB-5.02, JB-8.03, JB-0.03b), a **new ROADMAP §5b** on read-only
adversarial reviewers and orchestrator triage, `reviews/README.md`, and engine work: brush asked once
per dab, per-dab opacity cap, and a **tile read/write API on `GlPaintEngine`** (which JB-0.08a needs).
**Read §5b before I dispatch anything else — it may change these rules.**

