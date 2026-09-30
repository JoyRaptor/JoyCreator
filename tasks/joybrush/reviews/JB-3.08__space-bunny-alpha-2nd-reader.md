# JB-3.08 — second-reader review (NOT an independent cross-review)

| | |
|---|---|
| **Reviewer** | `openrouter/stealth/space-bunny-alpha` |
| **Spec reviewed** | `tasks/joybrush/specs/JB-3.08_three_finger_swipe_ui.md` (893 lines) |
| **Role** | careful second reader with no stake in the spec |
| **Verdict** | 🔴 **Back to the writer** — 1 BLOCKER, 6 MAJOR, 14 MINOR |

## Disclosure — read this before the findings

**I am the same model family as the spec's writer. I am not an independent reviewer and this must
NOT be recorded on the board as `xr`.** I have no stake in this spec, but I share the failure modes
of the model that wrote it, and a same-family second reading is weaker evidence than a cross-family
one. The board should treat the *verifiable* parts below — every `file:line`, every traced
arithmetic, every executed `contains()`/constant-pool argument — as findings, and treat the
judgement calls as needing a second, differently-family reader before this row is accepted.

I have tried to be harsher than the writer's own framing, not kinder. Where I expected the spec to
be wrong and it was not, I say so in "What the writer got right", with the arithmetic.

I did not edit any file except this report. I did not run gradle or git.

---

## Verdict

🔴 **Back to the writer.** Neither of the two load-bearing behavioural claims is wrong — I traced
both and both **hold**. That is the good news, and it is why this is not a rewrite.

The failures are in the layer above them: **one test cannot pass as specified** (T3a), one test sits
in a source set its own Step 5 says will not compile, one test in the non-vacuity table provably
does not catch the mutation it is claimed to catch, one test's stated non-vacuity example does not
fail, one algorithm has no termination rule, and one deliverable produces a blank badge in exactly
the mode the owner asked it to announce. None of those needs a new design; all of them need a
decision the spec does not currently make.

---

## BLOCKER

### B1 — T3a's constant-pool assertion forbids `copy`, and `Move` is specified as `data class`. Red on arrival.
**Severity: BLOCKER. Contradicts: the spec's own Contract (spec:326, 332, 335).**

Spec:623-627:
> "the class file's **constant pool**, read as ISO-8859-1 text, names none of `JbDocument`,
> `AnimOps`, `AnimResult`, `CelWork`, **`copy`**, **`undo`**, `SaveQueue`."

Spec:326, 332, 335 declare all three payload cases as `data class`:
```kotlin
data class PlayheadTo(val boardId: String, val frameId: String) : Move()
data class EndTick(val boardId: String, val frameId: String, val atEnd: Boolean) : Move()
data class WrapTick(val boardId: String, val frameId: String) : Move()
```
Kotlin generates `copy()` (no default args → `copy$default` is not emitted, `copy` is) and puts the
method **name** in the owning class's own constant pool as `CONSTANT_Utf8` + `CONSTANT_NameAndType`.
The same is true of `component1`, `toString`, `hashCode`, `equals`.

So the assertion "names none of … `copy`" is **false for the spec's own required implementation**.
The builder cannot write T3a as specified and reach "0 failures", and cannot fix it without deciding
something the spec never states: is `copy` banned from `Move$*` only, or from `SwipeFrames` /
`SwipeFramesKt` too? Those are different tests.

**Proof of the shape** — the precedent the spec points at does exactly this filtering, and for a
reason: `FilmStripNoSecondCopyTest.kt:190-193` says `Grab.Edge.holdAtDown` and
`StripStep.HoldChanged.holdFrames` "are in the spec, and a rule that forbade them would forbid the
contract". The same courtesy is owed to `copy`, and the spec grants it in prose (spec:627: "which is
why they are checked by name and the non-`Move` classes are the ones the assertion is really
about") — but the prose never changes the *scope of the pool scan*, so the literal instruction
still reds.

**Fix:** split the two clauses and say which classes each covers, e.g. scan `SwipeFrames`,
`SwipeFramesKt` and `Move` for `JbDocument`/`AnimOps`/`AnimResult`/`CelWork`/`SaveQueue`/`undo`, and
scan **only** `SwipeFrames` + `SwipeFramesKt` for `copy`; or drop `copy` from the pool list and
replace it with a *field-name* check (`Move$*` declares no field named `copy`).

---

## MAJOR

### M1 — T3a is a source/bytecode test written into `commonTest`. The spec's own Step 5 says that is a build failure.
**Contradicts: the spec's own Step 5 (spec:569-572) and the precedent it names.**

- Spec:621 puts T3a in `core/commonTest/.../anim/SwipeFramesTest.kt`. T3a needs `::class.java`,
  `klass.protectionDomain?.codeSource?.location`, `String(bytes, ISO_8859_1)` and a constant-pool
  read — all JVM-only.
- Spec:569-572: *"`SwipeGestureNoSecondCopyTest.kt` (in `jvmTest` — it needs `joybrushRoot()` and
  `::class.java`, and a source-level test in `commonTest` is a **build failure**, which has happened
  eight times in this project)"*.
- `FilmStripNoSecondCopyTest.kt:41-45` says the same and names all eight rows, JB-3.03 among them.
- The owner-area row (spec:8) lists `SwipeFramesTest.kt` under `commonTest` and the `jvmTest` file
  for T8a–T8c only.

The spec knows the rule and then breaks it three lines later. **Move T3a into
`SwipeGestureNoSecondCopyTest.kt` (or a second jvmTest file) and widen the owner area.**

### M2 — Mutation 4 ("`move` writes `latched`") passes T4a, T4b, T4c, T5a–T5c. The table says T4a catches it. It does not.
**Contradicts: the spec's own non-vacuity table, row 4 (spec:748).**

T4a's assertion order (spec:635-641) is: `badge(doc0) == BRUSH` → `displayed(doc0) == FRAMES` →
`move(−36)` → `end()` → `latched == null`, `displayed(doc0) == BRUSH`.

Now apply the mutation: `move` writes `latched = swipe.badge(<the doc the host last passed>)`.

- `displayed(doc0) == FRAMES` is asserted **before** the move → still passes.
- `move(−36)` overwrites `latched` to `BRUSH` — and **no assertion re-reads `latched` or `displayed`
  after the move.**
- `end()` clears `latched` to `null`, so the final `displayed(doc0) == BRUSH` passes.

T4b's only post-move assertion is `move(−36) is still ShowFrame(5)` — the step, not the latch.
T4c asserts `latched == BRUSH` after a *second* `begin` on `doc0`, which the mutation also produces.

**So `latched` being written by `move` is caught by nothing in the suite**, and it is the one
invariant Decision 6 exists to protect. **Fix: one line** — after `move(−36)` in T4a, add
`assertEquals(SwipeMode.FRAMES, latched)` and `assertEquals(SwipeMode.FRAMES, displayed(doc0))`.

### M3 — T8b's own non-vacuity example does not fail it. T8b is close to inert against a literal copy.
**Contradicts: the spec's own non-vacuity table, row 16 (spec:760) and T8b's note (spec:735-736).**

T8b (spec:730-736) asserts that no non-comment line in the three files **contains** any of:
`STEP_DP`, `WRAP_PUSH_STEPS`, `MIN_FLIPPABLE_FRAMES`, `LOCK_TRAVEL_DP`, `SIZE_PER_DOUBLING_DP`,
`OPACITY_SPAN_DP`, `MAX_SIZE`, `MIN_SIZE`, `MIN_OPACITY`, `TAP_SLOP_PX`, `TAP_MS`, `PEG_`.

- Spec:735 says: *"Non-vacuity: write `private const val MIN_FLIPPABLE = 2` and T8b reds while
  T8a's census also reds."* **`MIN_FLIPPABLE` does not contain the substring `MIN_FLIPPABLE_FRAMES`.**
  T8b does not red. Only T8a reds.
- Spec:760 (mutation 16, "`SwipeFrames.kt` given a `frames.size >= 2` check" → "T2d **and T8b**")
  is false for T8b on the same reason: a literal `frames.size >= 2` contains none of the twelve
  tokens.

T8b's real value is narrower than the spec claims: it catches only a line that *names* another
row's constant (e.g. `val step = ThreeFingerSwipe.STEP_DP`). It cannot catch a copy, because a copy
is by definition a differently-named literal. **Say that, or extend the token list to prefixes
(`MIN_FLIPPABLE`, `STEP`, `WRAP_`, `LOCK_TRAVEL`, `SIZE_PER_DOUBLING`, `OPACITY_SPAN`, `TAP_`) and
drop the exact-name framing.**

### M4 — `place`'s avoidance loop has no termination rule and no answer for "can never be clear".
**No expected value; a design decision the builder must make.**

`place`'s KDoc (spec:372-390): *"pushed **down** until its touch rectangle clears everything in
`avoid` by `gapPx`, then clamped inside `[safe]`"* — with no bound on the push.

The natural implementation is `while (avoid.any { … }) y += gapPx`. **If any `avoid` rect overlaps
the badge's x-range and extends to or past `safe.bottom`, that loop never terminates.** Nothing in
the spec stops it, and `place` returns a `Rect`, not a "could not place".

T6d covers the too-small safe area with `avoid = emptyList()` and so does not reach this. No test
pins what `place` answers when the badge cannot be made clear.

**Fix:** state the rule. Reasonable candidates — clamp the push at `safe.bottom − touch` and accept
the overlap; or push **left** when down is impossible (the KDoc says "down", so this needs
re-deciding); or return the clamped position and let the caller see the overlap. Whichever is
chosen, give it a test.

### M5 — In BRUSH mode `SwipeBadge` produces an empty string. The owner's sentence is "running figure / brush … **always** shows which". The cost is not recorded anywhere.
**Contradicts: `JOYBRUSH_BLUEPRINT.md:269-270`.**

The blueprint (§6 q1, verbatim): *"A small corner badge (running figure / brush) always shows which,
and tapping it overrides the automatic choice."*

`SwipeBadge`'s whole content surface is `place`, `touchRect`, `readout`. And
`readout(BRUSH, …) == ""` **always** (spec:405, pinned by T7a's `(BRUSH, 7, 12) → ""`).
Spec:792-794 forbids drawing a glyph, and Q5 cuts the glyph decision out of the row.

So after this row lands, on a canvas board the badge computes a 28 dp circle and draws **nothing**
in it, and `SwipeBadge` exposes no way for a host to learn *which* mode it is — the host must call
`SwipeGesture.displayed(doc)` and invent its own indicator, which is exactly the iconography Q5
defers.

To be fair: the **decision** is delivered and tested (`displayed()` is the mode, pinned by T4a/T5b).
What is missing is that the *rendering* is blank in one of the two modes, and neither Decision 8,
Q4, nor the Do-not list says so. **Q4 asks only about adding size. It should also ask what BRUSH
shows** — and the honest answer may be "a glyph, which means this row cannot be `:core`-only".

### M6 — The fixture `b4` is described as corrupt. `DocOps.validate` accepts it.
**Contradicts: the landed `DocOps.kt`.**

Spec:585-586: *"`b4` — the same SPRITE board **hand-edited to carry 5 `Frame`s**, which
`DocOps.validate` would call wrong."*
Spec:452-453: *"a **hand-edited** SPRITE board that somehow carries 5 `Frame`s → **still BRUSH** …
A corrupted file must not be able to hand a person a frame-flipping badge."*

I read all of `DocOps.validate` (`DocOps.kt:54-175`). **No rule objects to a non-ANIMATION board
carrying `frames`:**

- rule 4 (`DocOps.kt:78-88`) is gated on `if (b.kind == BoardKind.ANIMATION)` — skipped for SPRITE.
- rule 5 (`DocOps.kt:91-98`) checks only `grid`.
- rule 7 (`DocOps.kt:102-138`) reads `board.frames` only for the ANIMATION board a layer
  `animatedIn`.
- rules 6/8/9/10/11 say nothing about frames-on-a-sprite-board.

`validate(b4)` returns **`[]`** — b4 is a *valid* document. The fixture is still a good defensive
test and T1c still works; but the rationale ("a corrupted file") is false, and the spec has told the
builder something untrue about the model that motivates one of Decision 2's two load-bearing bullets.
Either fix the fixture (`validate` does not catch it — say so) or find a case that *is* refused.

### M7 — `SwipeFrames.commit`'s own contract takes `ThreeFingerSwipe.Step`, whose hierarchy contains `Step.Brush`; T3a's forbidden-type list names `Brush`.
**Contradicts: the spec's own Contract.**

T3a (spec:622-624) forbids any declared field's type from being "or reaching" … `Brush`.
`commit(board: Board, step: ThreeFingerSwipe.Step)` (spec:342) and the `when` at spec:471-472 both
name `Step.Brush` — `ThreeFingerSwipe.kt:136`.

As a **field**-only walk this is fine (`Move$PlayheadTo` holds two `String`s, `EndTick` a `Boolean`).
Following the `FilmStripNoSecondCopyTest` precedent — which walks `declaredMethods` return **and
parameter** types (`FilmStripNoSecondCopyTest.kt:98-104`) — it reds on the spec's own `commit`.
State "fields only" in the spec, and say why `Brush` is on a list belonging to a file whose job is
to handle `Step.Brush`.

---

## MINOR

| # | Finding | Proof |
|---|---|---|
| **m1** | `board.frames.size` is cited as **line 127** four times; it is **line 126**. The order claim (kind first) is unaffected — line 125 is the `kind` check, line 126 is the read. | spec:116-131, 443-444, 603, 749 vs `ThreeFingerSwipe.kt:125-126` |
| **m2** | Decision 8: *"`Axis` is private inside `ThreeFingerSwipe`"*. `Axis` is a **public** enum in `SizeOpacityDrag`; what is private is the field holding the instance. **Q4 states it correctly** (`ThreeFingerSwipe.kt:167`, `private var brush`); Decision 8 does not. | spec:519-520 vs `SizeOpacityDrag.kt:40,67` |
| **m3** | The `FilmStrip` paste (spec:163-176) omits `StripStep.HoldChanged(val doc: JbDocument, …)` and `StripStep.Nothing` — and `HoldChanged` is the one member of that family that **does** carry a `JbDocument`, which is precisely what Decision 3 argues from. | `FilmStrip.kt:373-385` |
| **m4** | Decision 5 attributes "wrapping is playback's business" to **Decision 12**. JB-3.03's Decision 12 is the rounding idiom; the no-wrap stepper is **Decision 16**. (The landed KDoc carries the same stale number, so this is inherited — but the spec repeats it.) | spec:484-485 vs `JB-3.03_film_strip.md:415,439` |
| **m5** | Header table (spec:26-29) says the old draft's view Decisions are "preserved as **Decisions 8, 9 and 10**". They are preserved as **Decision 11** (spec:540-559). | spec:26-29 vs spec:540-559 |
| **m6** | Glyph cross-reference is wrong twice: spec:22 and spec:792 say the glyphs question is **Q4**; it is **Q5** (spec:868). Decision 8's "Q4" (spec:521) is correct and points at the brush-numbers question. | spec:22, 792 vs 868 |
| **m7** | `SwipeGesture(val swipe: ThreeFingerSwipe = ThreeFingerSwipe())` hard-codes **density 1** (spec:237). On a real device that is 36 px per frame instead of 108. The constructor can express the fix; no Decision says to, and no test covers it. T5a's numbers are all density-1. | spec:237 vs `ThreeFingerSwipe.kt:50,152` |
| **m8** | T8b cites `FilmStripNoSecondCopyTest.isCommentLine` as "the filter it provides". It is **`private`** — the new test must re-implement those three lines, and T8b's whole comment-exception depends on them. | `FilmStripNoSecondCopyTest.kt:505-508` |
| **m9** | T2b's "symmetric run to the last frame and `Wrapped`, ending on `PlayheadTo("b-anim1","f2")`" gives **no dx values**. It is reachable (I traced `+216` → `EndTick f10`, `+180` → `Nothing`, `+144` → `WrapTick f1`, `+108` → `PlayheadTo f2`), and also reachable by a fresh gesture mirroring `ThreeFingerSwipeTest.kt:341-363`. Give the numbers. | spec:613-614 |
| **m10** | T1a's `assertEquals(0, SwipeFrames.commit(b1, Step.Brush(24f, 0.5f)).let { 0 })` is a **tautology as written** — it compares `0` to `0` and can never fail. It says "so a brush drag can never move a playhead", which the assertion does not test. Write `assertEquals(Move.Nothing, commit(b1, Step.Brush(24f, 0.5f)))`. | spec:597-598 |
| **m11** | T7b requires that `SwipeBadge.kt`'s **non-comment lines contain no `/`**. The natural implementation of `place` and `touchRect` — both of which halve a side — contains `touch / 2f`. As written this reds a correct implementation, or forces a `* 0.5f` convention the spec never states. (`zoom` and `screenPerDoc` are fine.) | spec:704-708 vs the arithmetic in T6a/T6b |
| **m12** | T3b's "6-step run" is unspecified; the mutation it targets (clamping/reordering `frames`) does not need six. | spec:629-631 |
| **m13** | T8c claims a density guard "**for each of the three files**". `SwipeFrames.kt` and `SwipeGesture.kt` contain no density arithmetic at all — only `SwipeBadge` does. | spec:737-739 vs spec:322-343, 237-299 |
| **m14** | T8a's scope phrase "and `Step`'s referents" would pull `ThreeFingerSwipe`'s own `STEP_DP` / `WRAP_PUSH_STEPS` / `MIN_FLIPPABLE_FRAMES` into a census whose expected list is exactly `["DRAWN_DP","TOUCH_DP"]` — which would then fail. Drop the phrase. | spec:717-720 vs `ThreeFingerSwipe.kt:306-312` |

Additional MINOR bookkeeping:

- `JbArchive` is in **androidkit** (`joybrush/androidkit/src/main/kotlin/…/androidkit/io/JbArchive.kt`),
  so T3a's *type-reachability* walk cannot name it from `:core:jvmTest`. It works only as a string
  check on the constant pool; the spec's first clause implies otherwise.
- Two other specs cite **"JB-3.08's case 19" / "JB-3.08 test 19"** for the source-file-reading trick
  (`JB-0.09_lobby_entry_and_chrome.md:237`, `JB-3.07_send_to_studio.md:273`). This draft renumbers
  that test to **T8b**, and the header table records the old draft's Q's and Decisions but **no test
  numbering**. Add the mapping.
- Header deviates from `SPEC_TEMPLATE.md`: `**Needs**` vs `**Depends on**`; `📝 Draft spec` vs the
  template's vocabulary (`SPEC_TEMPLATE.md:6`); the DoD omits the template's
  `INDEX.md updated` item (`SPEC_TEMPLATE.md:35`), and `INDEX.md` does not mention JB-3.08 at all.
- Mutation 6 is not executable as written: `FilmStrip.stepPlayhead(frameId: String?, delta: Int)`
  takes an **id and a delta**; `commit` has an **index**. "Route a FRAMES step through it" requires
  inventing a delta. Mutation 13 (`TOUCH_DP → 40f`) also reds T6a, T6b, T6c and T6e, not only T6f —
  under-reported, not wrong.

---

## My own independent answer to the two load-bearing claims

These are my answers, derived from the landed files. They are not confirmations.

### Claim A — "Mode latched at `begin`; `begin` is the only thing that changes it"

**TRUE, and it is true of the landed code already, before this row adds anything.**

I traced the state in `ThreeFingerSwipe.kt`:

- `private var mode = SwipeMode.BRUSH` — `ThreeFingerSwipe.kt:155`. The **only** write is
  `mode = badge(doc)` at **`ThreeFingerSwipe.kt:184`**, inside `begin`.
- `move` branches on it and nowhere assigns it: `if (mode == SwipeMode.FRAMES) return stepFrames(…)`
  — **`ThreeFingerSwipe.kt:213`**.
- `end()` sets `running = false` and `brush = null` and **does not touch `mode`**
  — **`ThreeFingerSwipe.kt:218-221`**; and `move` is gated on `running` at
  **`ThreeFingerSwipe.kt:210`**.
- `tapBadge` writes only `overrides` — **`ThreeFingerSwipe.kt:96-110`** — never `mode`.

So the *gesture's* mode cannot change mid-flight. What this row adds is not the gesture's mode but
the **badge's**: `SwipeGesture.displayed(doc) = latched ?: swipe.badge(doc)` (spec:273) stops the
badge following the document while a gesture runs — which `ThreeFingerSwipe.badge()` legitimately
does change under (`ThreeFingerSwipe.kt:75-80` reads `doc.activeBoardId` and `overrides` fresh every
call, and its own KDoc at `:72-73` says so).

One caveat the spec does not state: **"begin is the only thing that changes `latched`" is a
convention, not a type.** `latched` is `private set` (spec:252-253), which does make it
structurally true inside `SwipeGesture` — but nothing stops a host holding the injected `ThreeFingerSwipe`
and calling `swipe.tapBadge(doc)` directly mid-gesture. The spec's KDoc names the risk ("two owners
of 'is a gesture running' is the drift this project has paid for", spec:230-231) and then relies on
a promise. **This is currently safe**, because nothing in production constructs `ThreeFingerSwipe`
— a grep over every `*.kt` finds construction only inside `ThreeFingerSwipeTest.kt` (lines 82, 91,
125, 159, 172, 189, 217, 234, 256, 385, 395, 401, 441, 446, 456, 488, 513, 559) — so the row is not
fighting an existing owner. Nothing mechanical pins it, though.

### Claim B — "A badge tap mid-gesture is REFUSED, not deferred"

**TRUE, and it is achievable — but not by the landed API. It is achievable because refusal =
*not calling*.**

The landed API **cannot** express it. `tapBadge(doc: JbDocument)` returns `Unit`
(`ThreeFingerSwipe.kt:96`) and has **no** notion of a running gesture: `running` is `private`
(`:154`), never consulted by `tapBadge`, and there is no return value, no `GestureRunning` type, no
`canTapBadge`. If the row had needed the landed class to *decide*, that would have been a BLOCKER
and a change to a Built, cross-reviewed file.

It does not need to. `SwipeGesture.badgeTap` is new code in a file the row owns, and refusal is
literally "return `REFUSED_GESTURE_RUNNING` without calling `tapBadge`" (spec:284-289). So the
decision is expressible in this row's own files. **Not a BLOCKER.**

And refusal is genuinely **observable**, which is the part I checked hardest:

- `overrides` (`ThreeFingerSwipe.kt:66`) is written at exactly three places, all inside `tapBadge`:
  `overrides.remove(boardId)` at `:101` and `:106`, and `overrides[boardId] = flipped` at `:109`.
- `end()` (`:218-221`) does **not** touch `overrides` — its own KDoc says "The badge override stays"
  (`:217`).
- Therefore: a **deferred** tap would, at `end()`, flip `overrides["b-anim1"]` from absent to
  `BRUSH`, making `badge(doc1)` answer `BRUSH`; a **refused** tap leaves it `FRAMES` forever.

T4b's `end()`-then-assert-`swipe.badge(doc1) == FRAMES` line (spec:644-646) is therefore a real
discriminator, not a restatement. I checked the arithmetic on both branches of `tapBadge` for
`doc1` (ANIMATION, 10 frames, no existing override): `automatic(doc) = FRAMES` (`:113-114`), so
`badge(doc) != auto` is false, `flipped = BRUSH`, and the `flipped == FRAMES` guard at `:105` does
not fire — the tap writes `BRUSH`. Both a deferred and a forwarding implementation land on `BRUSH`,
which is why mutations 2 and 3 are caught by *different* assertions and both reds. That is well
constructed.

The one thing I would add: the spec's claim that `badgeTap` "does **not** call `ThreeFingerSwipe.tapBadge`
**at all**" (spec:286) is stated as a property of the *code*, and it is the right property, but like
Claim A it is enforced by nothing but review. Given this project's own habit of making conventions
mechanical with reflection, a census asserting that `SwipeGesture` has exactly the writers the spec
names would cost ten lines.

### Claim C — "Sprite cells are NOT frames → BRUSH, and a hand-edited SPRITE board carrying 5 `Frame`s is *still* BRUSH, because the kind check fires first"

**TRUE. I went to read those lines specifically expecting the order to be reversed. It is not.**

`ThreeFingerSwipe.kt:122-127`, verbatim:
```kotlin
private fun frameCountOf(doc: JbDocument): Int {          // 122
    val active = doc.activeBoardId ?: return 0            // 123
    val board = doc.boards.firstOrNull { it.id == active } ?: return 0   // 124
    if (board.kind != BoardKind.ANIMATION) return 0       // 125  ← the kind check
    return board.frames.size                              // 126  ← the count
}                                                        // 127
```

**The `kind` check is at 125 and fires first; `board.frames.size` is read at 126.** So for
`b4` (SPRITE, 5 `Frame`s): `frameCountOf` returns 0 at line 125 without ever reaching 126 →
`automatic` returns `BRUSH` (`:113-114`) → `badge` returns `BRUSH`. **Decision 2's second bullet and
everything downstream of it is sound.** The spec cites the read as line 127; it is 126 (m1) — the
line *number* is wrong, the **order** is right, which is the part that matters.

Two supporting facts I also verified rather than took on the spec's word:

- `automatic(doc)` is `frameCountOf(doc) >= MIN_FLIPPABLE_FRAMES ? FRAMES : BRUSH`
  (`ThreeFingerSwipe.kt:113-114`), and `MIN_FLIPPABLE_FRAMES = 2` (`:312`) — so 5 frames on a
  SPRITE board is genuinely unreachable, not merely unlikely.
- The model's own comments back the vocabulary argument: `frames` is "ANIMATION only, in play order"
  (`DocModel.kt:76`), `grid` is "SPRITE only" (`:77`), and `SpriteGrid` is a spatial
  `cols/rows/cellW/cellH` (`DocModel.kt:67`). Validated under two different rules —
  `DocOps.kt:78-88` vs `:91-98`. **But see M6: neither rule rejects `b4`, so "corrupt" is the
  spec's word, not the model's.**

**Non-vacuity of T1c, verified by me:** mutation 5 (reorder `frameCountOf` so the count is read
before the `kind` check) makes `frameCountOf(b4) = 5` → `automatic` = `FRAMES` → `badge` = `FRAMES`
→ T1c reds. That mutation is on a Built file and the spec correctly requires it to be made, run,
pasted, reverted and declared temporary (spec:762-763). **That is a good instruction and it is
right** — it is the only mutation in the table whose target the builder cannot reach from this row.

---

## The supersede claim — verified

The writer says the new file supersedes a draft of 2026-09-29 written against the pre-R25
`ThreeFingerSwipe`. I checked both halves the brief named.

**No orphan draft exists anywhere.** A recursive filename search for `*3.08*` returns exactly three
files: the spec under review, `JB-3.08a_three_finger_swipe.md`, and
`reviews/JB-3.08a__muse-spark.md`. The old draft was a same-path overwrite, as claimed.

**No dead contract is referenced.** `overrideBoardId` appears **nowhere** in the tree except the
spec's own sentence naming it (spec:13). `forgetOtherBoard` appears only as history in
`reviews/JB-3.08a__muse-spark.md:12`. Every symbol the new spec's Contract pastes or calls exists
in the tree today — I checked each one against the landed file, and the **pastes are structurally
faithful** (m3 is the only excerpt that is materially incomplete).

**Is the header table actually there, and actually true?** It is there (spec:12-29), and **four of
its five rows are exactly true**:

- **Q1** — "a `HashMap<boardId, SwipeMode>` that only `tapBadge` writes (`ThreeFingerSwipe.kt:66`)".
  Line 66 is `private val overrides = HashMap<String, SwipeMode>()`, and the only writes are at
  `:101/:106/:109`, all inside `tapBadge`. Also matches R25 verbatim
  (`LEAD_RULINGS.md:256-261`). **TRUE.**
- **Q2** — "Zero assignments in `badge()` (`ThreeFingerSwipe.kt:75-80`)". Lines 75-80 contain two
  `?: return`, one `overrides[boardId] ?:`, one call and one `return`. No assignment. **TRUE.**
- **Q4-moot** — consistent with Decision 8 and with `Axis` being unreachable from outside.
  **TRUE.**
- **Q3→Q6** and **Q6→Q1** renumbering — the Questions section really does number the finger-table
  question **Q6** (spec:880) and the compositing question **Q1** (spec:812). **TRUE.**
- **Q5 → "re-raised as Q4"** — **FALSE.** The glyphs question is **Q5** (spec:868), not Q4. Q4 is
  the brush-numbers question (spec:857). Same slip at spec:792. (m6)

And the closing paragraph's claim that the old draft's view Decisions are "preserved as Decisions 8,
9 and 10" is **false** — they are preserved as **Decision 11** (m5). They *are* preserved, which is
the substantive claim; only the pointer is wrong.

**So: no BLOCKER on the supersede claim.** It is honest, it is specific, and the references check
out. One loose end (m-list above): two other specs still cite "JB-3.08's case 19" for the
source-reading trick, and this draft records no test-number mapping.

---

## The dp arithmetic — re-derived independently

The brief flagged this as the place where a comment can pass for a pin. I re-derived everything from
the landed constants and the rulings.

**The literals.** `PaperGeometry.PEG_PITCH_DP = 44f` at **`PaperGeometry.kt:45`**, whose KDoc at
`:44` says verbatim "the house touch floor (R32)". `PEG_RADIUS_DP = 14f` at `:48`,
`PEG_HIT_RADIUS_DP = 22f` at `:54` — all three line numbers in the spec are correct. R32
(`LEAD_RULINGS.md:346-349`) lists "peg pitch **44 dp**, peg radius **14 dp**, hit radius **22 dp**",
so the house floor really is 44 and `TOUCH_DP = 44f` is the right number, not a coincidence.
`DRAWN_DP = 28f` traces to `JOYBRUSH_VISUAL_LANGUAGE.md:155` — "Floor | nothing below 28dp; almost
everything 40 or 44" — verbatim, and to `:189` "Round icon button | … **28dp circle** on `CTL`".
The gap `(44 − 28) / 2 = 8` is a *rule*, not a number the spec asserts; I re-derived it and it is
consistent everywhere it is used. The colour of that "without being a copy of it" remark about
`Eyedropper.DRAG_OFF_DP = 8f` (`:42`, verified) is coincidental-value reasoning, not derivation —
but the rule stands on its own, so nothing breaks.

**The pixel results, recomputed from scratch:**

| Test | Spec says | My derivation | |
|---|---|---|---|
| T6a | `Rect(1044, 52, 28, 28)` | centre = `1080 − 22 = 1058`, `44 + 22 = 66`; half-drawn 14 → `1044`, `52` | ✅ |
| T6a touch | `Rect(1036, 44, 44, 44)` | centre (1058, 66), half-touch 22 → `1036`, `44`; `right = 1080 = safe.right`, `y = 44 = safe.y` | ✅ |
| T6b @2.75 | `Rect(981, 66, 77, 77)` | `44 × 2.75 = 121`, `28 × 2.75 = 77`; halves `60.5`, `38.5`; `1080 − 60.5 − 38.5 = 981.0`, `44 + 60.5 − 38.5 = 66.0` | ✅ |
| T6b @2.75 touch | `Rect(959, 44, 121, 121)` | centre (1019.5, 104.5) − 60.5 → `959`, `44` | ✅ |
| T6b exactness claim | "exact in binary floating point at density 2.75" | 2.75 = 11/4 exact; 121, 77, 60.5, 38.5, 981.0, 66.0 all exact — and no rounding ambiguity on `.toInt()` because every value is integral | ✅ |
| T6b guard | density 0 / NaN / −2 → the density-1 answer | `SizeOpacityDrag.kt:58` and `ThreeFingerSwipe.kt:149` use the identical guard | ✅ |
| T6c | `Rect(1044, 68, 28, 28)` | touch (1036,44,44,44) vs close (1020,12,40,40): overlaps (`44 < 52`) ✅; push to `touch.y = 52 + 8 = 60`; drawn follows to `60 + 8 = 68` | ✅ |
| T6d | `Rect(0, 8, 10, 2)` | centre `(−12, 22)`; drawn `(−26, 8, 28, 28)`; clamp into (0,0,10,10) → x 0, y 8, w 10, h 2 | ✅ |
| T6f | `assertEquals(PEG_PITCH_DP, TOUCH_DP)` | `44f == 44f` | ✅ |

**The pin the brief asked about: it exists, and it is a real pin.** T6f (spec:697-699) is a direct
`assertEquals` on the two constants, and mutation 13 (spec:757) names the exact mutation that
breaks it. **A comment saying they are equal would not have been enough, and the writer did not
stop at a comment.** Two notes: the mutation-13 row under-reports (T6a, T6b, T6c and T6e all move
too), and `DRAWN_DP = 28f` is **explicitly PROVISIONAL** (spec:423, Q3) while T6a/T6b/T6c hard-code
28 — which the spec itself flags as "one constant and two test numbers" (spec:853-855). Correctly
handled.

**Two harder re-derivations I did not expect to hold, and they do:**

- **T5a's brush arithmetic.** `move(11,0) → Nothing`, `move(13,0) → Nothing`, `move(173,0) →
  Brush(20f, 0.5f)`, with the derivation `10 × 2^((173−13)/160) = 20`. The subtle part is why
  `13` produces *nothing*: `SizeOpacityDrag` re-bases the lock at **the sample that crosses the
  threshold** — `lockDx = dxScreen` at `SizeOpacityDrag.kt:98`, then `sizeAt` measures from `lockDx`
  (`:126`) — so `sizeAt(13) = 10 × 2^0 = 10`, unchanged, and `stepBrush`
  (`ThreeFingerSwipe.kt:295-302`) reports only a *change*. `move(173,0)` then gives
  `10 × 2^((173−13)/160) = 10 × 2^1 = 20.0` exactly. **Every number in T5a is right, and the test's
  "compute it from `SizeOpacityDrag.SIZE_PER_DOUBLING_DP` by construction" instruction
  (spec:661-663) is the correct way to keep it from drifting.**
- **T2b's wrap and ends.** I traced `+127, +216, +234, +252, +288` through `stepFrames`
  (`ThreeFingerSwipe.kt:243-283`) by hand: `round(127/36) = 4` → `4 − 4 = 0` → `Ended(0, false)`;
  push at 216 = 72 px and at 234 = 90 px, both under the 108 px `WRAP_PUSH_STEPS × step`, both
  still clamped to 0 → `Nothing`; `252 = 144 + 3 × 36` → `Wrapped(9)`; `288` → `288 − 252 = 36`, so
  `9 − 1 = 8` → `ShowFrame(8)`. Then I found the symmetric continuation that lands on the spec's
  `f2`: `+216 → EndTick("b-anim1","f10", true)`, `+180 → Nothing`, `+144 → WrapTick("b-anim1","f1")`,
  `+108 → PlayheadTo("b-anim1","f2")`. **The endpoint the spec states is reachable — it just does not
  give the inputs** (m9). And T2c's `−36 → PlayheadTo("b-anim1","f6")` with no tick is right too.

---

## What the writer got right that I expected to be wrong

Listed because the brief asked, and because these are the parts a same-family reader is most likely
to wave through. Each one is a place I expected a defect and found none.

1. **The `kind`-before-`count` order is genuinely first.** I read those lines specifically looking
   for the reverse, because the whole of Decision 2 rests on it and because a reorder is exactly the
   kind of thing that happens silently. It is correct (`ThreeFingerSwipe.kt:125` before `:126`), and
   the mutation that would break it is named and is the only non-vacuity mutation in the table that
   the builder cannot perform from this row's own files.
2. **"Refused" is achievable even though the landed API cannot say it.** `tapBadge` returns `Unit`
   and has no gesture awareness, so my first instinct was "the landed API cannot express this —
   BLOCKER". That instinct is wrong, and it is wrong for a reason worth keeping: refusal is the
   *absence* of a call, in a file the row owns. And the spec then does the harder thing — it proves
   the refusal is **observable** by choosing an assertion (`badge` after `end()`) that only a
   *deferred* implementation would break, which is possible only because `end()` provably does not
   touch `overrides`. That is a well-chosen discriminator, not a hopeful one.
3. **T4a's non-vacuity guard is built into the test, not bolted on.** Asserting
   `swipe.badge(doc0) == BRUSH` — *"the delegate disagrees with the latch — otherwise the test is
   vacuous"* — **before** asserting `displayed(doc0) == FRAMES` is the right way to stop the row's
   headline test from being a tautology. Most specs this size would not think of it.
4. **The dp arithmetic is not a comment.** The brief's suspicion was well aimed, and the writer
   answered it: T6f is an executable `assertEquals` on the two literals, mutation 13 names the
   mutation, and every pixel value in T6a/T6b/T6c/T6d re-derives exactly — including the
   claim that density 2.75 is chosen because every intermediate is exact in binary floating point.
   That is a claim I could have dismissed as hand-waving and it is true.
5. **T5a's brush numbers are right, and for a non-obvious reason.** That `move(13,0)` reports
   `Nothing` depends on `SizeOpacityDrag` re-basing the lock at the crossing sample, which is not
   stated in `SizeOpacityDrag`'s public contract in so many words. The spec got it right and got the
   `2^((173−13)/160)` exponent right too.
6. **Every line citation I checked in the landed files is correct** except one: `ThreeFingerSwipe`
   12/50/75/80/96/131-145/183/209/218/306/309/312, `SizeOpacityDrag` 58/81/156/168, `PaperGeometry`
   45/48/54, `DocModel` 67/76/77, `DocOps` 78-88/79/91-98, `FilmStrip` 51/288-309/373-375,
   `ViewTransform` 32-33, `CanvasGestures` 168/324/327, `Eyedropper` 42, `DefaultPresetsTest` 460.
   Given that `LEAD_RULINGS.md:342-344` lists "stale paths/line numbers" as one of the project's
   five recurring spec defects, this is a genuinely good result and it is what made the rest of this
   review possible.
7. **Every ruling citation checks out.** R25 (per-board map, `badge()` pure read), R32 (dp at the
   use site; 44/14/22), R19 (`MAX_SIZE_PX` not copied; `view.zoom` not its inverse), R3/R31 (no new
   serialised field), R30 items 1-3 (the lock order, and version numbers assigned at landing) — all
   present in `LEAD_RULINGS.md` with the content the spec attributes to them. The
   `JOYBRUSH_VISUAL_LANGUAGE.md` quotes (28dp floor, stroke 1.7, `genicons.py`) are verbatim.
8. **The row's central structural assumption holds.** `SwipeGesture`'s whole design rests on "the
   host calls `begin`/`move`/`end`/`badgeTap` on this and on nothing else". I checked: no production
   code constructs `ThreeFingerSwipe` today — only `ThreeFingerSwipeTest.kt` does. The row is not
   fighting an existing owner, and its Q1 can name four concrete integration points rather than
   inventing a seam.
9. **No orphan draft and no dead reference.** The supersede claim is honest on both halves the brief
   named, and the header table is 4/5 accurate rather than decorative.
10. **T7a contains a test the spec does not advertise.** `(FRAMES, 7, 5) → ""` is the case that
    stops the badge lying after a board loses frames mid-session — and combined with
    `displayed() == latched`, it means a mid-gesture frame loss cannot put `"7 / 1"` on screen. The
    spec's `readout` KDoc states the rule ("a badge that lies about a denominator is worse than one
    that says nothing") and T7a pins it. That is the same instinct as finding 3, applied to content.
11. **The tap-vs-drag split in Decision 11 is stated as exact complements of one number**
    (`centroid travel > TAP_SLOP_PX` ⇒ swipe; `≤` ⇒ redo). I verified `CanvasGestures.kt:199` and
    `:271` use exactly `TAP_MS`/`TAP_SLOP_PX` with `>` / `≤` in that polarity, and `:168` returns
    early for `pointerCount >= 2` so three fingers pinch today and must be stopped — which is
    exactly what Decision 11 says, at the right line number.
12. **It refuses to fake a view.** Q1 puts the badge's composited draw and the finger routing behind
    R30 item 1 and item 2 by name, with the specific files, and states the cost (a Phase 3 showpiece
    with no visible sign of the feature) rather than quietly widening the owner area. The `Do not`
    list's last entry — no Gradle, no build file, no resource, no string, no localisation — is the
    kind of line that saves a reviewer's afternoon.

---

## What I would not sign off, in order

1. **B1** — fix T3a's `copy` clause and its scope. Without this the row cannot reach "0 failures".
2. **M2** — add the two assertions after `move(−36)` in T4a. One line; without it the row's central
   invariant is untested while the table claims it is tested.
3. **M1** — move T3a to `jvmTest` and widen the owner area. The spec's own Step 5 already says this.
4. **M4** — give `place` a termination rule and a test for the un-clearable case.
5. **M5** — decide and record what the badge shows in BRUSH, and add the cost to Q4.
6. **M3** — state that T8b catches a *named reference*, not a literal copy, and fix its
   non-vacuity example and mutation 16.
7. **M6** — fix the "corrupt document" premise; the model accepts `b4`.
8. **M7** — say "fields only" in T3a, and explain `Brush` on that list.
9. The MINORs, in one pass: m1 (line 126), m2 (`Axis`), m3 (`HoldChanged`), m4 (Decision 16),
   m5 (Decision 11), m6 (Q5), m7 (density), m8 (`isCommentLine` is private), m9 (T2b inputs),
   m10 (the tautological assertion in T1a), m11 (the `/` rule in T7b), m12, m13, m14, plus the
   bookkeeping (INDEX.md, header keys, the "case 19" references).
