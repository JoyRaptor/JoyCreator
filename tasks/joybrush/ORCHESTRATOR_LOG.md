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

