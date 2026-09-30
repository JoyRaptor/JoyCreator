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

## WHAT IS NEXT

**Ready and dispatchable:** nothing in wave A remains. The next Ready rows are all
**app-file or Lead-owned** and blocked by R30's lock order — `JB-2.01` (chrome, first in
`JoyBrushActivity.kt`), `D.02` → `D.02c`/`D.05`, `JB-2.03a`, `JB-2.06b`.

**So the next work is spec authoring**, for the `⚪ Outline` rows whose Needs are all Built:
`JB-3.03b` (film-strip thumbnails — **the board link is dead, there is no spec file**), `JB-3.02b`
and `JB-2.11b` (the two view halves cut off by R30, both blocked on `JB-2.01`), `JB-2.05`,
`JB-2.22`, `JB-8.05` (the real-files probe, which JB-8.01b also waits on).

`JB-8.01b` is 🟦 Ready and **needs `JB-8.05`, which is `⚪ Outline`** — so it is not dispatchable
however tempting it looks. Its acceptance test is a real `.abr` file that does not exist in the
repo and never will (R8: importing what the user owns is fine, redistributing is not), so it is
waiting on the owner for `joybrush/testdata-local/abr/*/data.json`.
