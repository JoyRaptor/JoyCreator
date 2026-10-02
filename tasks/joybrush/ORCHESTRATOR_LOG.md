# JOY BRUSH — ORCHESTRATOR LOG

Handover file. Read this first. **The orchestrator alone edits `ROADMAP.md`, commits and pushes.**
Subagents never commit, push or touch the board.

Promoted 2026-09-28. Rewritten 2026-09-29 (end of the overnight session).
**Updated 2026-09-30, session 2 — read the OPERATIONAL section first, it changed.**

---

## STATE

`:core:jvmTest` **1214 tests, 0 failures** | `:androidkit:test` **153 tests, 0 failures**
(was 1114 / 132 at the start of this session, so **+100 core, +21 androidkit**, all green.)

Board: **5 rows moved 🟦 → 🟧 Built** this session. Specs on disk: **90**.

---

## ⚠ OPERATIONAL — THE BIGGEST CHANGE, AND IT IS NOT OPTIONAL

**This machine has 2.5 GB of free RAM, so there is exactly ONE Gradle JVM at a time, and the
orchestrator owns it.**

The previous session's rule was "subagents may run `./gradlew -p joybrush :core:jvmTest`". **That
rule is dead.** Five builders each running their own Gradle + Kotlin daemon would exhaust the
machine, and a build in the main folder corrupts the app watcher's cache (`build.log`).

**The rule now:**

1. **A builder NEVER runs gradle.** Not once. It writes code and tests, verifies every symbol by
   reading the landed file, and reports. This is stated in every brief and every fix round.
2. **The orchestrator runs every test**, in a clean worktree, one at a time.
3. `./gradlew -p joybrush` is **never** run in the main folder while the watcher is alive.

**The loop that works:**

```
# 1. dispatch up to 5 builders, disjoint owner areas, all editing the MAIN tree
# 2. sync their files into the clean worktree
$wt = "$env:TEMP\jbclean"
$files = (git diff --name-only -- joybrush) + (git ls-files --others --exclude-standard -- joybrush)
foreach ($f in $files) { copy main -> $wt }
# 3. run ONE gradle there
Start-Process cmd /c "gradlew.bat -p joybrush :core:jvmTest :androidkit:compileKotlin :androidkit:test > log 2>&1"
# 4. read the XML, hand every failure back to the agent that wrote it, verbatim
# 5. when green: copy the board into the worktree, commit there, push from there
```

A run takes **25–35 s** now. That is cheap enough to be the centre of the loop rather than an
afterthought, and it is why five rows landed in one session instead of three.

**After pushing from `$TEMP\jbclean`, sync the main tree's index** — `git reset --mixed origin/joy-creator`.
It moves HEAD and the index and **never touches the working tree**, so Muse's two modified review
files and the 32 untracked files stay exactly as they are. Do **not** use `--hard` or `checkout`.

---

## SESSION 3 — 2026-10-02, orchestrator "bunny" (this session)

`:core:jvmTest` **1508 / 0 / 0** (104 suites) · `:androidkit:test` **231 / 0** (20 suites).
Landed on `joy-creator`: **`93804965`**, **`2ae29249`**, **`6d6d670f`**.

**The one that mattered most was not a row at all.** `joybrush/core/build.gradle.kts` passed a
**String** to `fileTree(...)`, and Gradle reads a String as an **Ant include pattern**, not a
directory. With `JOYBRUSH_TESTDATA` set — *the only way to reach the real `.abr` corpus* — the
task died at execution with `Trailing char < > at index 58`. So **R44 item 1's guarantee (an
edited corpus re-runs the probe) was never in force, and no worktree could have run the real-file
test at all.** Fixed with `inputs.dir(...)`, the same call the three lines above already use.
After the fix `AbrRealFilesTest` went from **4 skipped** to **4 executed**. The lesson is not
"Do check your build files"; it is that **a build input declaration is code, and it had never
been executed on the path that matters.**

### Landed

| Commit | What | Result |
|---|---|---|
| `93804965` | muse's `JB-8.01b` (`.abr` vs four real files) + the `build.gradle.kts` fix | 1499/0/0, real probe executes; row → `🟩 Reviewed (xr)` |
| `2ae29249` | board: duplicate `JB-3.00` row deleted, `JB-3.04a/b` reverted to Outline, stale "no spec exists" corrected | 5 lines |
| `6d6d670f` | muse's `JB-3.03` F2+F3, bunny's `JB-9.01` audit fixes, `JB-2.03a` cancel rule | core 1508/0/0, `HexTileTest` 15/15, androidkit 231/0 |

### Audits filed (read-only, nothing committed by the auditors)

`JB-9.01_9.02` **0 BLOCKER, 4 MAJOR, 5 MINOR** · `JB-9.04_9.05` **0 BLOCKER, 3 MAJOR, 9 MINOR**
· `JB-2.02c_2.03a` **1 BLOCKER, 4 MAJOR, 11 MINOR**.

The BLOCKER is the best find of the session and it is a **degenerate predicate**: the long-press
eyedropper's cancel circle is drawn *on the touch point*, so `overCancel()` is true at t=0 and a
plain long-press-then-lift takes **nothing**. The spec's own acceptance check cannot pass. The fix
is a rule — *a cancel circle is a way back, so it is not a cancel until the touch has left it* —
and the rule landed in `Eyedropper` with tests. **The call site is in `JbCanvasView.kt`, a
Lead-only hot file, so both patches sit in `tasks/joybrush/held/` and the feature is still dead in
the app.** Question 1 in `LEAD_DESK.md`.

### Two auditors disagreed with the audit, and the builder was right

Worth recording as a ratio, not a story. The `JB-9.01` builder **disputed two findings with
reasoning** — the audit had quoted a *pre-`JB-9.03b`* snapshot, so its findings 3 and 8 described
an encoding that had already been replaced. It also corrected **my** diagnosis of its own compile
error: I said "an `Int` literal in a `ByteArray` needs `.toByte()`", and it pointed at three
pre-existing lines in the same file that do exactly that and ship today, because Kotlin adapts an
integer literal when the value fits — `128` was outside `Byte`'s range, and `= 0` was never an
error. **A reviewer that never runs anything is still a reader, and a builder that reads its own
compiler output carefully is worth more than either of us.**

### The no-gradle rule, measured

The `JB-9.01` branch **did not compile at all** — 1243 lines of tests, never built — and its
Python port was wrong three times about float32 vs float64: it reported a residual of "exactly
0.0" that was really **0.060**, 60 000× its own tolerance. One `compileTestKotlinJvm` in its own
worktree would have caught the type error in seconds. Question 5 in `LEAD_DESK.md` proposes
letting builders compile but never test.

---

## THE LESSONS THIS SESSION PAID FOR

**14. `fileTree(<String>)` is a PATTERN, not a directory.** It cost the real-file corpus
entirely, and it was invisible because the suite is green when the variable is unset. **The
symptom is a build that is red only on the path nobody takes.**

**15. I collided with Muse on JB-8.01b** by dispatching a builder before fetching the other
orchestrator's branches. My duplicate never compiled; Muse's was proven. Fix: *fetch the other
lane's branches before dispatching, not after.*

**16. Two overlapping Gradle runs in one worktree produce a plausible red that is not red** —
`Unable to delete ...\test-results\jvmTest\binary\output.bin`. I caused it myself. Also observed a
**822 MB daemon already resident from 07:17** (the owner's watcher), so the real headroom is
tighter than the nominal 2.5 GB.

**17. A board's Who cell is a CLAIM, not evidence.** `JB-3.04a/b` carried detailed, plausible
review verdicts for spec files that **do not exist on disk** — copy-pasted from `JB-3.03b` and
`JB-3.02b`. Muse made the same mistake independently (reviewing non-existent specs). New rule:
*the review file must exist on disk before a verdict is triaged.*

**18. Editing a CRLF + UTF-8 file from PowerShell corrupts it, and the corruption is invisible in
a diff summary.** `[System.IO.File]::WriteAllLines` silently normalised 319 CRLF endings and once
dropped an emoji entirely (`代` U+4EE3 replaced 🟧). Three false "the diff shows other rows
changed" scares followed. **Use the edit tool for repo files; never PowerShell text cmdlets.**
This is the same accident class as lesson 12, and it is now twice paid for.

---

## OPEN QUESTIONS FOR THE LEAD

Five, all in `LEAD_DESK.md`: the held `JbCanvasView` BLOCKER patch · whether to write the
onion specs or fold the rows away (the `OnionMath` extraction has **no owner**) · the 8.01
spacing constant · the `A - B²` clamp consumer · whether builders may compile.

**Also flagged, not asked of anyone:** the owner's main folder is **diverged — 7 commits ahead,
34 behind** (the 7 are Codex's region work). `git merge --ff-only` refuses. Per R8 I did not
rebase, reset, merge or resolve anything, and I ran no gradle there. All my work is on
`origin/joy-creator` and in worktrees, so nothing is stranded.

---

## LANDED THIS SESSION (all pushed)

| Row | What | Result |
|---|---|---|
| **JB-3.03** | film-strip core maths: cells, edge grab, drag, gestures | 28 tests, 25 commonTest green on the FIRST run |
| **JB-4.01** | sprite board core: grid by px/count, edge grab, sub-grid lines, steppers | 24 tests, 23 green on the first run |
| **JB-4.02** | the cell roll: tap order, holds, play modes, the preview walk | 21 tests |
| **JB-8.04b** | one DEFLATE implementation, one set of bomb caps, `zTXt` inflates | 27 new tests, 10 files |
| **JB-3.06c** | the four JB-3.06b audit fixes | 10 new tests, 1 vacuous assertion deleted |

All five builders were `openrouter/stealth/space-bunny-alpha` subagents. **`general`, not
`CoderAgent`** — still true, still the first thing to get wrong.

### The shape of the wave: 5 dispatch → 5 reports → 5 runs → 11 failures → 2 → 1 → 0

Seven build runs to land five rows. **Every one of the 11 failures was in a test, not in the
production code** — and five of them were a builder asserting a number it had not derived. That is
the opposite of the previous session's ratio, and the reason is the rule above: a builder that
cannot run anything writes assertions from arithmetic, and arithmetic written but not executed is
exactly where errors live.

---

## THE LESSONS THIS SESSION PAID FOR

**8. A BFS that guards on the wrong node hangs the whole suite.**
`FilmStripNoSecondCopyTest` walked `(ownerClass, Type)` pairs and guarded with a
`HashSet<Class<*>>` of *classes already seen*. `java.lang.Enum<E extends Enum<E>>` puts a
`TypeVariable` whose `bounds` contain `Enum<E>`, whose `actualTypeArguments` contain `E` again — so
the repeated node is a `TypeVariable`, never a class, the guard never fires, and the suite spins
forever. It cost a build cycle that had to be killed by hand. **The guard is a `HashSet<Type>` of
nodes ALREADY EXPANDED, and it must be threaded through every recursive call** — a `seen` parameter
that defaults to a fresh set at each entry point is decoration, not a guard. I hit this twice in one
file, and the third walk (CellRoll's) was unguarded recursion that terminated only by luck.
**A reflection walk in this repo is a hang waiting for the first self-bounded generic.**

**9. "Two probes inside one slot can never agree" is a sentence that reads true and is false.**
The CellRoll boundary sweep asserted no frame appeared twice in a row. Frame 5 *is* shown at
1499.999, 1500.0 and 1500.001 — because the forward leg's last frame and the backward leg's first
frame are the same frame, which is what ping-pong *is*. The builder then "fixed" it with a second
false rule ("a repeat is allowed at the boundary and nowhere else"), which failed on
`(1500.001, 1749.999)` — two instants inside one 250 ms slot, which agree **because** one frame
occupies the whole slot. **A claim about *instants* is arithmetic; a claim about an *emission list*
is a contract. Only the second belongs in a test.** The right fix was to delete both rules and keep
the per-probe equality against the reviewed clock, which already determines the answer.

**10. A test that fails on a *shape* it did not expect is telling you about your own filter.**
`SpriteBoard`'s census listed six collection fields; the walk found five. `Layer.frameCel` is a
**`Map`**, and `Map` is not a `java.util.Collection`, so a `Collection`-only filter drops it
silently. The tempting fix — delete the sixth name — removes a real hole in the check. The right fix
was to widen the predicate to `Collection or Map` and **assert the Map-typed fields separately**, so
narrowing it again fails on the specific line rather than as a stale list.

**11. Five builders, five "the TEST was wrong, here is the arithmetic".** Worth recording as a
ratio, not as five stories: `gen0` vs `gen1` (the id generator is global across a document, and
`addFrame` draws the frame's id before the cel's), a drag on f0 rather than f1, a *use* vs an
*entry* vs a distinct *cell*, a LOOP `periodMs` that is `rangeMs` and not `cycleMs`, and an
asserted-equals-the-opposite-of-what-it-checked in the inflate ceiling test. **In every case the
builder had to derive the answer from the implementation before touching the expectation, and in
every case the implementation was right.** If a builder is handed a red test and pastes the observed
value into the assertion, that is the moment the row stops being worth anything.

**12. A builder who reports his own accident is worth more than one who hides it.**
The JB-8.04b builder truncated a 95 501-byte spec to 2 872 bytes with a PowerShell here-string,
**reported it immediately**, and restored it byte-for-byte from a mirror he had made. Verified
independently: `git diff` on that spec shows 38 insertions and nothing else. Rule 4 of the brief
(never use PowerShell text cmdlets on repo files) exists because of that class of accident, and it
worked — the reporting is the other half of why.

**13. Two reflection tests failed on where Kotlin *puts* a constant, not on the claim.**
A `companion object`'s `const val` lands as a static field on the **outer** class, and the
`@Serializable` plugin gives `SpriteGrid` a generated `Companion` field. Both tests were asserting
a claim that was right and a location that was wrong. **Filter "class-typed static", never
"not an `int`"** — the second is a filter that goes blind to the exact thing the test exists to
catch.

---

## OPEN QUESTIONS FOR THE LEAD

Nothing is blocked on these; nothing was fixed in defiance of them.

1. **JB-4.01's Q1 stands OPEN at a 22 dp grid grab.** Reverting to 12 dp is one constant and moves
   tests 11–14. Unchanged from the last handover.
2. **JB-4.01's test 18 was rewritten by the builder, not by the spec's author.** The spec's
   "each change exactly one of `cellW`/`cellH`/`cols`/`rows`" is **unattainable for the count
   axes** — `byCount` divides the board it is given, so 7→8 columns on a 500 px board is 62 px, and
   a cell edge that stayed put would be a 512 px grid on a 500 px board. The builder read it as
   "one setter argument" and asserted that, plus the re-filled cell. **This needs a ruling** — it
   is a spec sentence, not a code question.
3. **JB-4.02's roll is session-only** (R36 Q1, cost stated in the spec). A person who spends ten
   minutes on a walk cycle and closes the drawing loses it. Unchanged, still the Lead's.
4. **JB-8.04b: the builder edited one line outside the quoted blocks** — the `because` assembly at
   `KritaImport.kt:953-957`, so Test 16's required sentence (`"could not be read out of the
   bundle"`) exists. E.3's block is verbatim and the STORED case is unaffected. **Confirm or
   revert**; reverting means asserting the cause instead of the sentence.
5. **JB-8.04b: two stale sentences the do-not list does not name were left alone.**
   `KritaImport.kt:60-62` still says the one change to `PngChunks.kt` was a single constant, and a
   test name still promises a DEFLATE fallback its body no longer has.
6. **JB-3.06c: `PngChunks.kt` is outside the owner area, so Decision 1's "name each other on both
   sides" is half-done.** `AnimExport.kt` names `PngChunks.kt`; nothing names back.
7. **JB-3.03 and JB-4.02 filled gaps the spec did not decide** — `frameAt(-Inf)`, empty-board
   totals, overflow clamps in `Long`, `fitted` writing the grid as well as the rect, and the
   `stepped`/`dragged` refusal on a null grid. All are now covered by green tests rather than only
   by prose. Worth a skim: they are decisions someone made, not decisions the spec made.

---

## SECOND HALF — the runway emptied, so the work changed shape

**The Lead landed 24 commits while I worked** (`JB-2.01` the compact screen chrome, `D.02a`,
`D.02`, `D.05`, `JB-2.03a`+`D.02c`, `JB-0.09`, `JB-1.06`, the R9 Sable tuft engine, then `JB-2.04`,
`JB-2.23`, the colour wheel and the brush settings drawer). Two consequences:

**1. `R30 item 1` is now satisfied, so `JB-3.02b` and `JB-3.02`'s view half unblocked.** The board
had them waiting on `JB-2.01`'s cluster for two days.

**2. Every Ready row drained, and the only two left are not dispatchable.** `JB-2.06b` wants
`JbCanvasView.kt` (R30 item 2, the Lead's) and `JoyBrushActivity.kt` (R30 item 1).
`JB-8.01b` needs `JB-8.05`, which is still `⚪ Outline`, and its acceptance test is a real `.abr`
file that will never be in the repo (R8). So: **spec authoring**, which is what I did.

### Three specs written, cross-reviewed, and sent back — all three came back BLOCKED

| Row | Spec | xr verdict |
|---|---|---|
| **JB-3.03b** | `specs/JB-3.03b_strip_thumbnails.md` — **the dead link is now live** | 2 BLOCKER, 4 MAJOR, 10 MINOR → revised |
| **JB-3.08** | `specs/JB-3.08_three_finger_swipe_ui.md` | 1 BLOCKER, 7 MAJOR, 14 MINOR → revised |
| **JB-3.02b** | `specs/JB-3.02b_paper_overlay_view.md` | 3 BLOCKER, 7 MAJOR, 11 MINOR → revised |

All three are now `📝 Draft spec`, **not `🟦 Ready`**, and they should stay that way until a
different-family reader has looked. See the disclosure below.

### ⚠ THE DISCLOSURE THAT MATTERS MOST IN THIS LOG

**Every subagent available to me is `openrouter/stealth/space-bunny-alpha`.** So the
cross-reviews above are **same-family and are not independent reviews** — the board records that
in each row's Who cell, in the `xr` sigil sense it does *not* claim `xr`. It still earned its
keep, emphatically: it found **six BLOCKERs across three specs**, and every one was a defect
invisible to the writer and to the compiler. But a same-family reader shares the writer's failure
modes, and the JB-3.08 writer said so about its own spec unprompted, which is the right instinct.

**Six BLOCKERs, and the pattern is the lesson:** *not one of them was a wrong number or a bad
arithmetic.* Every one was a claim the spec made that its own contract could not deliver.

- **A test that could not pass.** JB-3.08's T3a forbade the constant pool from naming `copy`
  while its contract declared the payload cases `data class` — and Kotlin generates `copy` into
  each class's own pool. Red on arrival. A builder would have had to invent a scope to fix it.
- **A number that only ONE test could see.** JB-3.03b's un-premultiply divided colour by the
  *weight sum* instead of by *alpha*, so it returned a premultiplied array labelled straight —
  which **is** the black fringe. All 22 tests had uniform alpha and could not see it. The
  overwhelmingly likely "fix" was to bend the one failing test to the code.
- **A predicate the spec never wrote down.** JB-3.03b's budget: tests 7 and 8 were *the same
  board with one frame moved* and demanded opposite answers, and no predicate satisfied 6, 7 and 8.
- **Two `JoyBrushActivity.kt` region counts wrong.** JB-3.02b said three; it is five, and it said
  three in four places.
- **`shown` never wired to `visibility`** — so the peg bar would have shipped **visible** on the
  Note 9 with five live TalkBack nodes reading "Play/Mode/Onion/Cadence/Export" wired to `= Unit`,
  which is precisely what `JoyBrushActivity.kt:355` forbids. It contradicted the spec's own
  Decision 22, its own Region 1 note, and its own T3 claim in the same sitting.
- **A KDoc that produced no tested value.** `pegBarWidthPx`'s formula gave 72 for every count;
  its tests wanted 248 for five and 72 for one.

**So: a spec's most dangerous sentence is the one that asserts a consequence rather than stating a
number.** Six of six BLOCKERs were consequences. The numbers were fine — the reviewer re-derived
JB-3.03b's entire scale table from first principles and found **one** wrong, out of ~40.

### 🔴 THE PLAN HOLE — a new board row, `JB-3.00`, and it blocks NINE rows

Found by the JB-3.02b cross-review and confirmed three independent ways: **the screen cannot
create an animation board, or any non-canvas board.** No row on the board owns it.
`JbCanvasView.kt:876` builds `newDocument` with `BoardKind.CANVAS`; a tree-wide grep for
`BoardKind.ANIMATION` hits only `core/`, tests and `JbColors.kt:66`; and `JoyBrushActivity.kt`
contains no `JbDocument` at all.

**JB-3.08 is not merely unprovable — it is DEAD CODE.** Its entire predicate is
`board.kind != ANIMATION -> 0` (`ThreeFingerSwipe.kt:125`), so `frameCountOf` is always 0,
`automatic()` always answers `BRUSH`, and its `activeBoardId` read has **no caller anywhere**.

**Blocks:** JB-3.02b, JB-3.03b, JB-3.04a, JB-3.04b, JB-3.05, JB-3.05a, JB-3.08, and the view
halves of JB-4.01 and JB-4.02. **One thin row, not nine dead ends** — and it is app-file work in
`JoyBrushActivity.kt`, so it is the Lead's to rule on before it can be specified.

### The main tree went stale twice, and how I handled it

The Lead pushed 24 commits during this session, so the main folder's working copy was twice ~20
commits behind `origin`. **It is a stale *working copy*, not uncommitted work** — proven by
`git diff <old-base>`, which showed the 19-commit delta with exactly **two** genuinely modified
files (Muse's review notes, uncommitted by design).

The refresh, which I ran twice and which is safe because only those two files are ever at risk:

```powershell
# 1. hold the two files with a hash
# 2. git reset --hard origin/joy-creator      # untracked files are NOT touched
# 3. copy them back, verify SHA-256 matches
```

Both times: **byte-identical restored, untracked count unchanged (64, then 67).** Never
`--hard` without the hash step — that is the difference between refreshing a tree and eating
somebody's afternoon.

## WHAT IS NEXT

**Dispatchable now:** nothing. Every 🟦 row is app-file work or waiting on the owner.

**The highest-value thing in the queue is `JB-3.00`**, and it is small: a phone affordance that
adds a board of a chosen `BoardKind`, plus wiring `activeBoardId`. Nine rows are unprovable
without it and one of them (JB-3.08) is dead code today. It needs a Lead decision because it
edits `JoyBrushActivity.kt`.

**Then, in order:**
1. **A different-family read of the three draft specs.** They have been cross-reviewed once, by
   their own family. Nothing marks them `🟦 Ready` and nothing should until that is done.
2. `JB-8.05` (the real-files probe) — it is what unblocks `JB-8.01b`, and it needs the owner's
   handful of real files in `joybrush/testdata-local/`, not a decision.
3. `JB-0.08c` — the rest of the save/open row, next in R30's lock order, and the Lead already
   ruled the Open-overwrites-your-drawing case.
4. `JB-3.04a`/`b` (onion skin) need D.02 → `OnionMath` extracted first, per R34's ordering.

**Unchanged and still owed:** the owner's Note 9 checks, and the questions listed above.
