# Adversarial review — JB-2.05a Selection masks (`tiles`→`internal`, `intersect` rewrite, `ellipse` guard, `fitsIntPixels`)

- Reviewer: claude (second adversarial pass; **muse-spark reviewed this task first** —
  `tasks/joybrush/reviews/JB-2.05a__muse-spark.md`, which filed a BLOCKER + a MAJOR + 2 MINOR and
  then an addendum recording 1 and 2 as fixed).
- Task status: 🟧 Built. Suite run by me at HEAD: `./gradlew -p joybrush :core:jvmTest` → 659 tests,
  1 failure, **not** in this task (`ThreeFingerSwipeTest`, JB-3.08a). `SelectionMaskTest` green.
- Spec reviewed: `tasks/joybrush/specs/JB-2.05a_selection_mask.md` (contract, Decisions 1–7,
  tests 1–10, Questions 1–3).
- §5b: I edited only this file. No git. No source edits.
- Severity: BLOCKER / MAJOR / MINOR per ROADMAP §5b.

**Verdict: 1 MAJOR, 1 MINOR.** The four items I was told to attack — the `tiles`-is-`internal`
change, `tileKeys`/`tile(key)`, the rewritten `intersect`, the `ellipse` radius guard and the
`fitsIntPixels` overflow guards — are all **correct**, and I say why below. My MAJOR is a *fifth*
door in the same file that neither reviewer filed.

---

## Finding 1 (MAJOR — unbounded allocation, ~10 GB, from an input the validator accepts): `invert`
## is the only coverage door with no size budget

**File:line.** `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/select/SelectionMask.kt:213-231`.

```kotlin
fun invert(within: RectPx): SelectionMask {
    if (within.w <= 0 || within.h <= 0) return EMPTY
    require(within.fitsIntPixels()) { ... }            // :215  — Int overflow only
    val out = HashMap<Long, ByteArray>()
    val yEnd = within.y + within.h
    val xEnd = within.x + within.w
    for (y in within.y until yEnd) {                    // :222  — w × h iterations
        for (x in within.x until xEnd) {
            val c = 255 - coverage(x, y)
            if (c == 0) continue
            val arr = writable(out, Tiles.key(tileOfPx(x), tileOfPx(y)))   // :226 — 64 KB per tile
            arr[indexInTile(x, y)] = c.toByte()
        }
    }
    return SelectionMask(finalise(out))
}
```

`writable` (`SelectionMask.kt:402-415`) allocates a **fresh 65 536-byte tile** per unseen key. There
is **no `MAX_SELECT_SPAN` check anywhere in this function** — I grepped every `.kt` under
`joybrush/core/src` for `MAX_SELECT_SPAN`; the only uses are `Lasso.kt:80` and `Resample.kt:183`.

**The input.**

```kotlin
SelectionMask.EMPTY.invert(RectPx(0, 0, 100_000, 100_000))
```

`fitsIntPixels` (`:382-385`) passes: `w > 0 && h > 0 && 0L + 100000 <= Int.MAX_VALUE && 0L + 100000 <= Int.MAX_VALUE`.
Then `1e10` pixel iterations, `ceil(100000/256)² = 391² = 152 881` tiles × 65 536 B =
**10 018 000 000 bytes ≈ 10.0 GB** of live heap before a single `OutOfMemoryError`.

**And the file itself states the rule this door breaks.** `SelectionMask.kt:355-362`:

> *"The largest bounding box a polygon may have… 16384 is 64 × 64 tiles, so a legal mask is at most
> 4 MB of coverage — **the same spirit as `render.MAX_REGION_PX` and `fill.MAX_FILL_PX`**, and stated
> here rather than imported so that this file stays arithmetic with no dependency on either of them."*

Every other door applies it: `polygon` and `ellipse` (which both go through `Lasso.kt:80`, "span >
`MAX_SELECT_SPAN` → `EMPTY`"), and `Resample.warp` (`Resample.kt:183`). `invert` is the one that
allocates coverage from a `RectPx` alone, and it does not.

**Even the smallest interesting case is expensive.** `RectPx(0, 0, 20_000, 20_000)` — a box only
22 % over the documented cap — costs `79² = 6 241` tiles ≈ **409 MB**, where `SelectionMask.rect` of
the same box returns `EMPTY` for free via `Lasso.kt:80`. So the two ways of asking for the same
rectangle disagree by two and a half orders of magnitude in memory, and neither says so.

**Reachability, stated honestly.** There is **no production caller of `invert` yet** — I grepped
every `.kt` under `joybrush/`; the only call sites are `SelectionMaskTest.kt:445, :457, :651, :667`.
So nothing OOMs today. But the input is not exotic: `RegionRenderer.kt:157-158` states that
*"`DocOps.validate` calls any `w > 0, h > 0` board rect a valid document"*, and the natural caller
is `mask.invert(board.rect)` for "invert selection", which is exactly what JB-2.05b's transform box
and JB-2.06b's fill tools will do. `Board.rect` is a plain `RectPx` off a decoded file, and
`DocJson.decode` deliberately does not validate (the design mimo's JB-3.01 F1 relies on for the fps
guard). So this is the same reachability class the project already treats as in-scope for the
`fps` and `holdFrames` guards.

**Not a spec contradiction — a gap the spec does not name.** Decision 7 scopes the cap to
*"a **polygon** whose bounding box exceeds 16384 × 16384"*. So the code is spec-conformant and the
**spec is silent about `invert`**. Per §5b that goes to the **Lead**: either extend Decision 7's
budget to every door that allocates coverage, or rule that `invert` is the one door allowed to be
unbounded (which I would argue against — the file's own sentence at `:358` already says it is not).

**Suggested test (belongs in the source tree, not here — §5b):**

```kotlin
@Test
fun anInvertBoxPastTheSpanCapIsRefusedRatherThanAllocatingTenGigabytes() {
    // Control: the documented cap, which every OTHER door already honours.
    assertTrue(SelectionMask.EMPTY.invert(RectPx(0, 0, MAX_SELECT_SPAN, 4)).tileCount() > 0)
    val over = RectPx(0, 0, MAX_SELECT_SPAN + 1, MAX_SELECT_SPAN + 1)
    // 65² tiles ≈ 277 MB if allocated; the suite must not get there.
    assertEquals(0, SelectionMask.EMPTY.invert(over).tileCount(), "past the cap")
}
```

---

## Finding 2 (MINOR — a false number in a KDoc that justifies a constant): the 360-gon's error is
## ~12× smaller than claimed

**File:line.** `SelectionMask.kt:346-349`:

> *"The polygon a circle is made of. **360 points put the flat of each side 0.06 % inside the true
> curve**, which is three orders of magnitude below the sampling error the antialiasing already has,
> so the point count is not a parameter anybody needs to think about."*

Two readings, both wrong, and I checked both:

* **Sagitta** (the inward flat of one side, the literal reading): a 360-gon on a circle of radius `r`
  has chords subtending 1°, so the max inward deviation is `r·(1 − cos 0.5°) = r·3.8065e-5` =
  **0.0038 % of `r`**. Claimed: 0.06 %. **16× too large.**
* **Area deficit** (what a shape's "0.06 % inside" would normally mean): the inscribed 360-gon has
  area ratio `sin(2x)/(2x)` at `x = π/360`, i.e. `1 − 5.077e-5` = **0.0051 %**. Claimed: 0.06 %.
  **12× too large.**

**Why it is MINOR and not more.** The error is in the *safe* direction (an over-estimate), and the
conclusion survives either value: 4×4 supersampling quantises coverage to steps of 1/16 = 6.25 %, so
0.06 % *is* three orders of magnitude below it, and so is 0.005 %. The conclusion "the point count
is not a parameter anybody needs to think about" is correct. But it is a numeric false claim in the
one place a future reader would go to decide whether `ELLIPSE_STEPS` needs revisiting — and a reader
who recomputes it will find the KDoc wrong, which is how these files lose credibility. Fix the
number (0.005 % for area, 0.004 % for the sagitta) and the sentence stands.

---

## Verified CORRECT — the four items I was told to attack, plus the rest

1. **The `tiles`-is-`internal` change is right and complete.** `SelectionMask.kt:37`
   `class SelectionMask internal constructor(internal val tiles: Map<Long, ByteArray>)`. The public
   surface is exactly `coverage` (`:40`), `bounds` (`:53`), `isEmpty` (`:89-90`), `tileKeys`
   (`:98-99`) and `tile(key)` (`:109`) plus the five factory/ops doors. I grepped every `.kt` under
   `joybrush/` for `.tiles`: the only non-test reader is `Resample.lift` (`Resample.kt:69`,
   `mask.tiles[entry.key]`), which is the same module and **only reads** — it copies into a fresh
   `remainingTile`/`liftedTile` at `:74-75`, so it cannot reach `FULL_TILE` either. The
   `FULL_TILE` corruption hole the class KDoc describes (`:27-35`) is genuinely closed.
2. **`tileKeys` / `tile(key)` do what they say.** `tileKeys` is `tiles.keys.toSet()` — for a set of
   size 0/1 Kotlin returns `emptySet()`/`setOf(x)`, for larger a fresh `LinkedHashSet`; either way
   the caller gets a set it owns and cannot use to reach the map. `tile(key)` is
   `tiles[key]?.copyOf()` (`:109`) — a genuine copy, so writing into it cannot corrupt a shared
   `FULL_TILE`. **The "64 KB a call" figure is exact**: `BYTES_PER_TILE = Tiles.SIZE * Tiles.SIZE`
   (`:365`) = 256² = 65 536 B = 64 KiB.
3. **The rewritten `intersect` is correct, and I re-derived the identities it claims.**
   `SelectionMask.kt:179-198` iterates `o.tiles`, skips keys `this` lacks (`:183`
   `tiles[entry.key] ?: continue`), copies only visited tiles (`:186`), and multiplies
   `(av·bv + 127) / 255` (`:193`). Checked:
   * *Commutative* — both orders visit the same key set and `av·bv` is symmetric. The pre-fix bug
     muse-spark filed (`a.intersect(b)` returning `a` for disjoint tiles) is structurally gone: a
     `this`-only tile is not in the loop at all.
   * *`bv = 0` → `0`* — `(av·0 + 127)/255 = 127/255 = 0` in integer division. ✓
   * *`bv = 255` → `av`* — `(255·av + 127)/255 = av + 127/255 = av` (truncating). ✓ So the
     comment at `:191-193` is literally true.
   * *`bv = 1`* — `(av + 127)/255 = 0` for every `av < 128`. That is `round(av/255)`, i.e. the
     correct "1/255 coverage is nothing", and it is the same rule `subtract` uses at `:156`.
     Consistent.
   * *Rounding is exactly round-to-nearest* — `floor(av·bv/255 + ½) = floor((av·bv + 127.5)/255)`,
     and for integer `av·bv` there is no integer in `(av·bv + 127, av·bv + 127.5]`, so
     `floor((av·bv+127)/255)` is the same number. ✓
   * *No `FULL_TILE` leak* — `a` is a local `copyOf()`, so `finalise` (`:425-434`) substituting
     `FULL_TILE` for an all-255 result is safe. ✓
   * *`subtract`'s twin loop is NOT the same bug*, and the KDoc at `:175-177` explains why
     correctly: a key missing from `o` means "subtract nothing", and `a − 0 = a`. I agree, and the
     asymmetry is forced by the two different identities, not a copy-paste slip.
4. **The `ellipse` radius guard is right, for the reason the KDoc gives.** `SelectionMask.kt:292`
   `if (!(rx > 0.0) || !(ry > 0.0)) return EMPTY`. The comment at `:286-291` is exactly right about
   the trap: `rx <= 0.0` is **false** for a NaN, so the obvious guard lets NaN through, whereas
   `!(rx > 0.0)` is **true** for NaN and catches it at the door. I also checked the two cases the
   guard deliberately does not handle, and both still return `EMPTY` downstream, so the promise at
   `:284-285` ("A zero or negative radius, a NaN anywhere, or a box past the cap all come back
   `EMPTY`") holds:
   * `rx = +∞` — passes the guard (`∞ > 0`), produces `±∞` points, dies in `Lasso`'s non-finite
     check → `EMPTY`.
   * `cx`/`cy`/`rotation` NaN — `cos(NaN) = NaN` → all 360 points NaN → `Lasso` → `EMPTY`.
   * `rx = ry = 1e9` (finite, over the cap) → `Lasso.kt:80` → `EMPTY`. ✓
   And the negative-radius failure mode the comment describes is real: `rx·cos(t)` with negative
   `rx` is the same 360-gon rotated half a turn, i.e. a *non-empty* mask that is not the ellipse
   asked for — so the guard is fixing a wrong answer, not just tidying.
5. **`fitsIntPixels` is correct, and its "the lower bound needs no half" argument is sound.**
   `SelectionMask.kt:382-385`. `x` and `y` are already `Int`s, and `w > 0 && h > 0` is required, so
   `x.toLong() + w` cannot underflow (the Long sum's minimum is `Int.MIN_VALUE + 1`) and the only
   failure mode is the top. I checked the three named cases:
   * `RectPx(Int.MAX_VALUE - 5, 0, 10, 10)` → `fitsIntPixels` **false** → `rect`/`invert` **throw**
     (`:215`, `:263`) rather than returning a silent `EMPTY`. This is the fix the spec's Question 2
     asked for, and it is right: `EMPTY` is indistinguishable from a legitimately-empty selection.
   * `RectPx(Int.MIN_VALUE, Int.MIN_VALUE, 100, 100)` → fits; `until` over `MIN_VALUE + 100` is
     well-defined; `tileOfPx` (`:456-459`) floors correctly, so the negative side is fine.
   * `RectPx(0, 0, 0, 10)` / `(0, 0, 10, 0)` → `EMPTY` at `:214`/`:262` before the `require`, as
     the KDoc promises. ✓
   The two doors disagreeing on purpose (`:362` budget for shapes, `require` for boxes) is
   explicitly explained at `SelectionMask.kt:205-211` and in the spec's Question 2, and I think the
   reasoning is right: "a shape too big to rasterise is refused data, a box too big to name is a
   bug". (Finding 1 is the third case neither of them covers.)
6. **`rect` really is pixel-exact, and the KDoc's argument is the right one.** `SelectionMask.kt:249-255`
   claims integer corners make every one of the 16 sub-samples of a pixel fall on the same side.
   Checked: pixel `(x, y)` covers `[x, x+1)`, and the samples are at `x + (i + 0.5)/4` for `i = 0..3`,
   i.e. `x + 0.125 / 0.375 / 0.625 / 0.875` — all strictly interior, so with integer corners no
   straddling pixel exists and coverage is exactly 255 inside / 0 outside. This is why the marquee
   is not antialiased, and the claim is the reason. ✓
7. **`fromMask`'s two guards are correct.** `SelectionMask.kt:317-343`. `n = w.toLong() * h.toLong()`
   is widened before the multiply (so the `require` at `:320` is exact, not a wrapped `w*h`), and
   the origin check at `:323-327` prevents `originX + col` wrapping to a negative coordinate and
   writing bytes into another tile — which the KDoc at `:314-316` describes exactly. Soft bytes are
   kept, not just 0/255 (`:334-335` only skips `v == 0`), as the contract requires.
8. **`add` is `max`, not `a + b`, and the stated consequences hold.** `SelectionMask.kt:118-134`
   plus the per-byte `if (bv > a[i]) a[i] = b[i]` at `:130`: no pixel can pass 255 and wrap, and
   `a.add(a) == a` pixel for pixel. The `writable`/copy discipline is right: `this`'s tiles are
   copied at `:124` *before* the write loop, so the shared `FULL_TILE` cannot be reached.
9. **The identity returns are harmless.** `add` returns `this` when `o` is empty (`:119`) and `o`
   when `this` is empty (`:120`); `subtract` returns `this` (`:144`); `intersect` returns the
   **shared `EMPTY` singleton** (`:180`). So `a.intersect(b) === SelectionMask.EMPTY` is observable.
   With the class frozen immutable (no mutator reaches any array: `coverage` returns `Int`,
   `tile` returns a copy, the ops all copy) this is an identity leak and not a bug — I checked that
   there is no `ByteArray` reachable from outside the module at all, which is the whole point of
   the `internal` change. Not filed; noted so a future reader does not "fix" it by adding a
   `distinct()` and costing an allocation per op.
10. **Decision 1 (floor division, never `/`) is honoured everywhere.** `tileOfPx` (`:456-459`) is the
    written-out floor division, `indexInTile` (`:462-465`) uses it, `coverage` (`:41`) uses it,
    `fromMask` (`:331, :337`) uses it, and `Resample.pixelAt` (`Resample.kt:433-434`) uses it. I
    checked the negative case by hand: `tileOfPx(-1) = -1 / 256 → 0`, `-1 % 256 = -1 < 0` → `0 - 1 = -1`. ✓
    `bounds()` (`:62-63`) reconstructs tile origins as `Tiles.tx(key) * Tiles.SIZE`, consistent.

---

## Recommendation

- **Finding 1 → Lead** (the spec does not scope a budget to `invert`, so this is a contract
  question, not a code fix). It is the only thing standing between JB-2.05b/2.06b and a
  `mask.invert(board.rect)` on a hostile or hand-edited document. If the answer is "cap it like
  `Lasso` does", it is four lines and the test above is the proof.
- Finding 2: correct the number, keep the sentence.
- The rest of this task is **clean**. The `internal`-by-default decision, the copying `tile()`, the
  `intersect` rewrite and its written-out rounding identities, the NaN-safe `ellipse` guard and the
  Long-before-add overflow checks are all correct as built, and the spec's Questions 1–3 are the
  right questions to have asked. I would let this task go to 🟩 Reviewed (xr) once Finding 1 is
  ruled on.
