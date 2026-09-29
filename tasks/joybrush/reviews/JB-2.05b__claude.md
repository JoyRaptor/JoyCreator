# Adversarial review — JB-2.05b Transform maths (`Resample.over` source-over fix, `Homography.then` KDoc)

- Reviewer: claude (second adversarial pass; **muse-spark reviewed this task first** —
  `tasks/joybrush/reviews/JB-2.05b__muse-spark.md`, which filed the destination-over BLOCKER, a
  spec finding on Decision 5, and 2 MINORs; an addendum records the BLOCKER fixed).
- Task status: 🟧 Built. Suite run by me at HEAD: `./gradlew -p joybrush :core:jvmTest` → 659 tests,
  1 failure, **not** in this task (`ThreeFingerSwipeTest`, JB-3.08a). `HomographyTest` /
  `ResampleTest` green.
- Spec reviewed: `tasks/joybrush/specs/JB-2.05b_transform_and_resample.md` (contract, Decisions 1–5,
  tests 1–7, builder Q1–Q5). **Per my brief I do not re-file Decision 5's "±1" claim** — it is the
  known, deliberately red, already-referred gap (spec Q1, spec Q4's closing paragraph).
- §5b: I edited only this file. No git. No source edits.
- Severity: BLOCKER / MAJOR / MINOR per ROADMAP §5b.

**Verdict: 1 MAJOR, 1 MINOR. Both are false claims — and one of them was reported to me as already
fixed when it is not, in two files, plus the spec.**

---

## Finding 1 (MAJOR — FALSE CLAIM, present at HEAD in two files plus the spec): the `then` composition
## order is documented backwards in **both** the implementation and its own test's header — and the
## test bodies are right

My brief told me: *"the `Homography.then` KDoc that was corrected from 'scales first' to
translate-first"*. **It was not corrected.** The same error is in two places and the spec then
asserts one of them is correct.

### 1a — `Homography.kt:64-73`, the KDoc contradicts itself in consecutive sentences

```kotlin
/**
 * This first, then [next] — `p.then(q).apply(x) == q.apply(this.apply(x))`, and the returned
 * matrix is the plain matrix product `q * this`.
 *
 * The ORDER IS THE WHOLE POINT and it is the opposite of the usual reading of the word
 * "then", which is why it is spelled out here and tested in `HomographyTest`: `translate(10, 0)`
 * `.then(scale(2, 2))` **scales first and moves second**, and the other order gives a different
 * picture.                                                            // ← FALSE
 */
fun then(next: Homography): Homography = Homography(mul(next.m, m))
```

`mul(n, p)` (`Homography.kt:220-230`) is the matrix product `n · p`, so `mul(next.m, m) = next · this`
and `(next · this)·x = next·(this·x)`. **`this` is applied first.** Line 66 says so correctly.
Line 70–71 says the opposite, about the same operator, three lines later.

### 1b — `HomographyTest.kt:31-33`, the test file's own header contradicts the test body 155 lines below

```kotlin
 *  - `then` IS "THIS FIRST". So `translate(10, 0).then(scale(2, 2))` applied to (1, 1) scales the
 *    point to (2, 2) and then moves it to **(12, 2)**, while the same two in the other order give
 *    **(22, 2)**. Both numbers are exact integers and both are asserted.          // ← BOTH WRONG
```

versus the body, `HomographyTest.kt:186-196`:

```kotlin
val move = Homography.translate(10.0, 0.0)
val grow = Homography.scale(2.0, 2.0, Pt(0.0, 0.0))
val a = move.then(grow).apply(Pt(1.0, 1.0))
assertNear(22.0, a.x, 1e-9, "move.then(grow) x")     // translate(10,0).then(scale(2,2)) == 22
val b = grow.then(move).apply(Pt(1.0, 1.0))
assertNear(12.0, b.x, 1e-9, "grow.then(move) x")     // scale(2,2).then(translate(10,0)) == 12
```

**The header has the two results exactly swapped.** I verified by hand rather than trusting either:
`(1,1) --translate(10,0)--> (11,1) --scale(2,2)--> (22,2)`; `(1,1) --scale--> (2,2) --translate--> (12,2)`.
So `translate(10,0).then(scale(2,2))` is **22**, not 12 — and the body asserts 22.

### 1c — the spec asserts the wrong one is correct

`JB-2.05b_transform_and_resample.md:164-170` (Q5) says the one-line KDoc fix was not made because
`Homography.kt` is outside the fix's owner area, and then:

> *"`HomographyTest` already asserts the numbers that prove it: (1, 1) goes to `(12, 2)` in that
> order and `(22, 2)` in the other, and the class KDoc at `HomographyTest.kt:31-33` says so in as
> many words."*

Both halves of that are wrong. The test asserts `(22, 2)` in that order, not `(12, 2)`; and
`HomographyTest.kt:31-33` says the *opposite* of the truth. So the spec's own "this is already
documented correctly, only the source KDoc is wrong" analysis rests on a misreading of both files.

**Severity.** Not a BLOCKER: the **code is correct** and the **assertions are correct** — a caller
who trusts the test bodies gets the right transform, and `thenAgreesWithApplyingTwice`
(`HomographyTest.kt:200-211`) independently pins `p.then(q).apply(x) == q.apply(p.apply(x))` on
three non-trivial points. I file MAJOR rather than MINOR because (i) it is a **false claim about a
public operator whose entire purpose is to be composed**, in the two files a reader goes to
precisely for that, and (ii) a reader who trusts either KDoc writes `h1.then(h2)` the wrong way
round and gets a visibly wrong picture — for a transform tool, that is the class of bug this
project's reviews exist to catch. (Strict §5b severity would call a comment MINOR; I am calling it
MAJOR on the false-claim rule, and the orchestrator can downgrade.)

**muse-spark's Finding 2** filed this as MINOR and cited only `Homography.kt:69-71`. I **AGREE** with
the finding and add (a) the **second** location in `HomographyTest.kt:31-33`, which is the more
dangerous of the two because it sits directly above a test body that says the opposite, and (b) the
fact that it was **reported to me as fixed and is not**. Either the fix was never applied or the
report of it is wrong; either way the "Definition of done" evidence for Q5 is not there.

---

## Finding 2 (MINOR — FALSE CLAIM in a shipped KDoc, cross-module, in a file JB-2.05b does not own):
## `Resample.kt:518-525` asserts the GLSL contract for the pre-existing `GlPaintEngine` blend
## functions, and the function it names is not in the pre-existing set

**File:line.** `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/select/Resample.kt:522-525`:

```kotlin
 * `out = top + bottom * (1 - top_alpha / 255)` on all four channels, which is `ONE,
 * ONE_MINUS_SRC_ALPHA` — the blend `GlPaintEngine` sets once, and the one the GPU has. The
 * alpha channel gets the same rule as the colours and not a special one: alpha is a channel,
 * and a source-over that raised the alpha and left the colours alone invents opacity.
```

**The arithmetic is right; the "the one the GPU has" attribution is false for what that sentence is
about.** `GlPaintEngine` uses `glBlendFunc` in exactly two places, and both are the *stroke commit*
and *layer draw* paths, not a tile transform:

* `GlPaintEngine.kt:349` — `glBlendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)`
  inside `addDabs` (dabs into the **stroke buffer**, which holds coverage, not colour).
* `GlPaintEngine.kt:437` — `glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)` once before the layer loop
  in `draw`.

`Resample.over` is the **post-warp recomposite of premultiplied RGBA8 tiles** — the layer pixels as
`writeTile`/`readTile`/`replaceTiles` store them. There is no `over` in the GL path, and
`Resample.kt:16-17` itself says so ("the same layout `GlPaintEngine.readTile` and `replaceTiles`
speak, so a warped result can be handed to the undo step without a repack") — *layout*, not blend.
So the sentence is a **layer-composite** justification attached to a **tile-recomposite** function.
The nearest true statement is that it is the *W3C/GL-conventional* source-over the engine's draw
path uses, so the recomposite agrees with how those pixels will next be drawn.

**Why MINOR and not more.** No wrong pixel: the formula is correct premultiplied source-over, the
identities are right, and the KDoc's *substantive* warning ("alpha is a channel, a source-over that
raised the alpha and left the colours alone invents opacity") is the real content and is correct.
The defect is that the named authority is a different function. It matters because the spec's
Q4 records that this sentence was written **specifically to fix a destination-over bug** ("The KDoc
already claimed those semantics, so the code contradicted its own documentation") — a fix justified
by a claim that is itself not quite true is a claim that will not stop the next reader from trusting
it. Note also the file already does this correctly elsewhere: `:518` cites
`GlPaintEngine.kt:279` and `:367` for the *original* destination-over KDoc, and those line numbers
pointed at the old text; the current lines are `:349` and `:437`.

---

## Verified CORRECT — the `over` fix and the two new tests specifically

1. **`over` is genuinely premultiplied source-over, and I re-derived it.**
   `Resample.kt:310-327`: one `ta = t[at+3]` per pixel (`:310`), used for **all four** channels
   (`:319`, `c in 0..3`) — including alpha, so `a = ta + round(ba·(255−ta)/255)`. The operands are
   `tv + round(bv·keep/255)`, i.e. **the top is the source**. Matches
   `glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)` semantics and the W3C compositing definition. The
   `ta == 255` early exit (`:311-317`) is exact (factor zero ⇒ result is the top in every channel) and
   the KDoc at `:279-280` correctly notes that an opaque *backdrop* is **not** an exit case.
2. **The clamp comment is right.** `:322-325` — "for premultiplied input (`tv <= ta`, `bv <= 255`)
   the sum cannot pass 255, because `ta + 255·(255−ta)/255 = 255`." For the alpha channel
   `tv = ta`, so `out = ta + (255−ta) = 255`; for a colour `tv <= ta` gives
   `tv + (255−ta) <= 255`. And `over`'s callers cannot supply non-premultiplied data: `lift`
   (`:86-93`) splits a premultiplied byte by a monotone `round(v·m/255)`, and `warp`'s `toByte255`
   (`:500-504`) rounds a convex combination of premultiplied values, so `c <= a` survives rounding
   on both sides. I traced both. ✓
3. **`overAgreesWithASecondModelOfTheSameBlend` is a genuinely independent model and is
   non-vacuous.** `ResampleTest.kt:598-605` is written in normalised Double from GL's
   `s' = s + d(1 − sa)`, with a **single** rounding at the end (`floor(v·255 + 0.5)`), versus the
   implementation's integer `floor((bv·keep + 127)/255)`. Different units, different arithmetic, a
   different rounding path — the KDoc's argument at `:590-597` is exactly the right one, and it is
   the property that makes the test worth having.
   The fixture at `:613-627` sweeps **all 256 top alphas** with premultiplied-valid colours
   (`(i*61) % (ta+1) <= ta` for every channel), and asserts **exact** integer equality. The
   decisive non-vacuity case is at `:648-658`: premultiplied black `(0,0,0,128)` over opaque white
   → **`[127,127,127,255]`**, which the old destination-over formula answered `[255,255,255,255]`.
   That assertion **fails against the pre-fix code**, so the test is not decorative. I checked the
   arithmetic: `0 + 255·127/255 = 127` for the colours, `128 + 255·127/255 = 128 + 127 = 255` for
   alpha. ✓
4. **`overHasTheThreeIdentitiesSourceOverMustHave` pins the three identities, and the old code broke
   two of them.** `ResampleTest.kt:677+`: (a) opaque top ⇒ the top, byte for byte (`:705-707`) —
   under the old per-channel backdrop factor this answered the *backdrop*; (b) fully transparent
   top ⇒ the backdrop untouched (`:708-710`) — under the old formula `tv == 0` short-circuited to
   `b` and **ignored `top_alpha` entirely**, which is how semi-transparent black over white came out
   white; (c) `over(x, EMPTY) == x` and `over(EMPTY, x) == x`. All three would have failed pre-fix.
   Non-vacuous. ✓
5. **Inputs are not modified and nothing is shared with the result.** `over` copies the
   single-sided tiles (`:295`, `:299`) and allocates a fresh tile for the two-sided case (`:302`);
   `lift` copies the mask-absent tile (`:71`) and allocates both splits fresh (`:74-75`). ✓
6. **`warp` is inverse-mapped and therefore structurally free of the forward-mapping hole class.**
   `Resample.kt:150` takes `h.inverse()` **once**, and `:247`/`:229-234` map each destination pixel
   centre `(x+0.5, y+0.5)` back. The pole-inside-a-tile limitation of the bounding-box approach is
   stated at `:118-123` rather than hidden, and `sample`'s non-finite guard (`:389-390`) turns the
   affected pixels transparent instead of writing a NaN byte — which is the honest answer and the
   one the KDoc at `:361-375` argues for.
7. **The minification threshold's discriminant handling is the subtle bit and it is right.**
   `sampleSpan` (`:468-491`) clamps the discriminant to 0 (`:487`) rather than testing it, and the
   KDoc at `:459-462` explains why that is load-bearing: for `J = kI`, `S²` and `4·det²` are both
   `4k⁴`, so the discriminant is *exactly* zero, and a `<= 0` refusal would disable the 3×3 grid for
   every uniform scale. The measurement is of the **inverse** map at the **tile** centre (`:200-206`),
   so it answers "source pixels per destination pixel", which is the right direction; and it is
   paid only where needed, since `NEAREST` never takes the path (`:204-206`).
8. **`ADDRESS_LIMIT` guards fire before `.toInt()`.** `:181-183` then `:185-188`. I checked the
   order matters and it is right: `floor(1e300).toInt()` is `Int.MAX_VALUE`, so the check has to
   precede the conversion. `ADDRESS_LIMIT = 1e9` and `max − min > 16384` together bound the result
   to at most 65×65 = 4 225 tiles. ✓
9. **`lift`'s exactness invariant is correctly stated and correctly implemented.** `:44-47`:
   `lifted = round(v·m/255)`, `remaining = v − lifted`, "the two are exact complements, so
   `lifted + remaining` is the original byte in every channel with no rounding at all." Trivially
   true by construction (`:90-92`), and it is the invariant that *does* hold — as the spec's Q1
   says, unlike Decision 5's `±1`.
10. **`Homography` is otherwise sound.** `m` is copied on the way in (`:35`, so `IDENTITY` cannot be
    aliased into the zero matrix); `apply` refuses on `w` (`:57`) *and* on a non-finite image
    (`:60`) — I checked why both are needed; `inverse` measures the determinant before dividing
    (`:89`) and returns `null`; the adjugate is transcribed term by term and I re-derived all nine
    cofactors against `:93-99`; `solve8` uses partial pivoting (`:267-285`) with the pivot floor
    checked before any division, and a `back substitution` pivot re-check at `:300`; the degenerate-quad
    refusal is doubled (explicit twice-area at `:242-246` plus the pivot fallback) and a **non-convex
    bow-tie is deliberately not refused** (`:140-141`), which I agree with — a bow-tie has an honest
    projective map, it is just inside-out, and returning `null` would be wrong about a solvable case.

---

## Recommendation

- **Finding 1** — two one-sentence corrections (`Homography.kt:70-71`, `HomographyTest.kt:31-33`),
  plus a correction to the spec's Q5 paragraph, which currently mis-describes both files. Neither
  is a code change; both are in the "false claim" class this project treats as real defects. If the
  earlier report that this was fixed is accurate, then the fix was reverted or never landed and the
  `Definition of done` evidence for Q5 should be withdrawn.
- **Finding 2** — re-attribute the sentence to the compositing convention rather than to
  `GlPaintEngine`'s two `glBlendFunc` calls, and refresh the stale `:279`/`:367` line references to
  `:349`/`:437`.
- Everything else in this task is **clean and genuinely well proved**. The `over` fix is the right
  fix, the second-model test is the right shape for "did we get the blend operator right", and the
  three identities are the right pin. Decision 5's `±1` remains open and correctly red; I did not
  re-file it.
