# JB-9.01 / JB-9.02 — fixes for the adversarial audit (bunny-fixes)

Row owner: the owner of `SurfaceMaps.kt`, `HexTile.kt` and their `commonTest` files.
Nothing was run under Gradle (R43/R44 item 6), so **no test in this branch has been executed**. Every
number in this report and in the tests was derived by reading the landed file and, where a figure came
from the shipped asset or from the maths, checked against a Python port of the same formulas. What is
still unproven is listed at the end.

The audit was **not in this worktree**. `tasks/joybrush/reviews/JB-9.01_9.02__bunny-audit.md` exists only
in the owner's folder, uncommitted there. It was read from there and not copied, so that committing this
branch cannot collide with the owner's copy of the same path.

Line numbers: `pre:` is the landed file at `cd726f0b` (what the audit read), `post:` is after this
branch's edits.

---

## Findings, one line each

| # | Severity | Verdict | One line |
|---|---|---|---|
| 1 | MAJOR | **PARTLY FIXED, partly DISPUTED** | The test no longer re-derives the hex centre and a new test pins the formula as numbers — but the functions were never dead, and a test outside this row already went red under the mutation the audit describes. |
| 2 | MAJOR | **FIXED** | `gammaWeights` now reads `HEX_GAMMA` instead of writing `w*w*w`, and a new test pins the reading. |
| 3 | MAJOR | **DISPUTED** | On the landed code there is no asymmetry: byte 127 decodes to exactly 0 and 126/128 are the two encode steps either side of flat. The audit's figures are from the pre-JB-9.03b encoding. |
| 4 | MAJOR | **FIXED (the claim), DEFERRED (the consumer)** | `A − B²` is quantisation error and the KDoc now says so with the arithmetic; the consumer is JB-9.06, which is not this row's area. |
| 5 | MINOR | **FIXED** | `encodeSlope` refuses a non-finite slope by name. |
| 6 | MINOR | **FIXED** | `lattice` guards `hexTexels`, which is the argument it divides by. |
| 7 | MINOR | **PARTLY FIXED, DEFERRED for the board** | The test's KDoc now says what the number measures and what it does not; the board row itself is out of this row's area. |
| 8 | MINOR | **DISPUTED (already fixed)** | The assertion the audit quotes was replaced by `differing == 0` in JB-9.03b, which is already landed. The audit read a stale copy. |
| 9 | MINOR | **CONFIRMED, not a bug** | Inherent to `A = round(255·h²)`; the audit's "0 or 1" at B ≤ 11 is wrong — it is exactly 0 there. Now derived and pinned. |
| — | the audit's "not reproduced" gap | **FIXED** | A new test pins the rotation's sign, which octant spread cannot see. |

---

## Finding 1 — `centreX` / `centreY` · PARTLY FIXED, partly DISPUTED

### What the audit said, and what is wrong with it

The audit says the functions are "called only from `readAt`" (`HexTile.kt` pre:182-183) and that they are
"dead to the whole suite". **Both halves are wrong**, and I checked both by reading:

- `rg -n "centreX|centreY"` finds two more call sites, both production code, both outside this row:
  `PaperRaster.kt` pre:26 (`localFrame`) and `PaperRaster.kt` pre:44 (`sampleLocal`, the CPU export path).
- `PaperRasterTest.localFramePreservesGlobalHashAndSamplesAtLargeSignedCoordinates`
  (`PaperRasterTest.kt`:46-61) calls `localFrame`, and its line 50 asserts
  `abs(f.localOriginX) < 64 && abs(f.localOriginY) < 64`. Under the audit's mutation (both bodies `0.0`)
  that bound fails. I ported `localFrame` and computed it: at corner `(1e7, -1e7)` the correct local
  origin is `(-16.000000, -15.721407)` — inside the bound, because a hex cell is 32 texels wide and the
  local frame is rebased at the cell — and under the mutation it is `(4000000.0, -4000000.0)`, which is
  62 500 times the bound. Same at the other corner. So the mutation the audit describes **does** turn a
  named test red; it is just not one of the nine in `HexTileTest`.

The audit is right about the narrower claim, though, and that part is worth fixing: `HexTileTest` test 4
(`HexTileTest.kt` pre:178-179) and test 7 (pre:297-298) both wrote `H·(i + j/2, j·√3/2)` out again
instead of calling the functions, so the suite the audit looked at really did follow any change to the
functions.

### What changed

1. `HexTileTest.aHexCentreLandsBackInsideItsOwnHex` (post:272) now calls `HexTile.centreX(i, j, 12.0)`
   and `HexTile.centreY(i, j, 12.0)`.
2. `HexTileTest.aRotatablePaperTurnsEachHexsSlopeAndKeepsItsLength` (post:419) now calls
   `HexTile.centreX(i, j, 11.0)` and `HexTile.centreY(i, j, 11.0)`.
3. New `HexTileTest.theHexCentreIsThePointTheSpecWroteDown` (post:253) pins the value as numbers, so a
   change to either function is no longer followed along by every caller.
4. `HexTile.centreX`'s KDoc (post:111-118) and the class KDoc (post:22-26) now name the shader's inline
   copy and say which side is pinned.

### Derivation of every number in the new test

At `H = 10`, with `√3 = 1.7320508075688772935…`:

| hex | x = `10·(i + j/2.0)` | y = `10·(j·√3/2)` |
|---|---|---|
| (2, −3) | `10·(2 − 1.5)` = **5.0** | `−15√3` = **−25.98076211353316** |
| (0, 1) | `10·0.5` = **5.0** | `5√3` = **8.660254037844386** |
| (−1, 0) | `10·(−1)` = **−10.0** | **0.0** |
| (3, 5) | `10·(3 + 2.5)` = **55.0** | `25√3` = **43.301270189221932** |

Every x is a sum of halves and tens, so it is exact in Double and the assertion carries **no tolerance**.
Every y carries a Double `√3`: up to half an ulp of the √3, half an ulp of `j·√3` and half an ulp of the
final scale, which is about `3·1.11e-16` of relative error, plus half an ulp of the decimal literal
itself. At magnitude 43 one ulp is `7.11e-15`, so the worst case is about `1.8e-14` and the tolerance is
`1e-13` — about six times that, and four orders of magnitude below every mutation listed in the test.

I checked all eight values against a Python port of `centreX`/`centreY`: every x error is exactly 0, and
the y errors are 0, 0, 0 and `−7.105e-15` (two ulps at 43.3).

### Mutation reasoning

| Mutation | Which named test goes red | By how much |
|---|---|---|
| both bodies → `0.0` | `PaperRasterTest.localFramePreservesGlobalHashAndSamplesAtLargeSignedCoordinates` (`:50`) | 4e6 against a bound of 64 |
| both bodies → `0.0` | `theHexCentreIsThePointTheSpecWroteDown` | 5.0, 25.98, 10.0, 43.30 |
| both bodies → `0.0` | `aHexCentreLandsBackInsideItsOwnHex` | `lattice(0.001, 0, 12)` never contains hex (−3,−3); fails at the first pair |
| both bodies → `0.0` | `aRotatablePaperTurnsEachHexsSlopeAndKeepsItsLength` | all 40 samples collapse onto one point, 1 of 8 octants, against a bound of 6 |
| `j / 2.0` → `j / 2` (Int) | `theHexCentreIsThePointTheSpecWroteDown` | x(2,−3) = 10.0 against 5.0 |
| drop the `H` multiply | `theHexCentreIsThePointTheSpecWroteDown` | y(2,−3) = −2.598 against −25.981 |
| `SQRT3` → `1` | `theHexCentreIsThePointTheSpecWroteDown` | y(2,−3) = −15.0 against −25.981 |
| `i + j/2` → `i − j/2` | `theHexCentreIsThePointTheSpecWroteDown` | x(2,−3) = 35.0 against 5.0 |

`j * (SQRT3 / 2.0)` is **not** a mutation: halving a Double only drops an exponent, so
`j · RN(SQRT3/2)` and `RN(j · SQRT3)/2` are the same single rounding. Said in the test so nobody files it.

---

## Finding 2 — `HEX_GAMMA` is a dead knob · FIXED

### Which of the two was the lie

The constant was not the lie about its **value**: JB-9.02 Decision 2 fixes the contrast at 3 and there is
no derivation of that number anywhere in the spec, so 3 is a decision, not a derived result. What was the
lie is presenting it as a **knob** — spec line 30 lists it in the contract block and line 72 writes
`w'_k = w_k^HEX_GAMMA / Σ w^HEX_GAMMA`, so a reader takes it as the thing to turn. The code turned nothing.
So: the constant stays, and the code now reads it.

### What changed

`HexTile.gammaWeights` (pre:157-166, post:202-217) applies the gamma as a whole number of multiplies,
reading it from `HEX_GAMMA` by default, and refuses a gamma that is not one. It became `internal` so a
test can reach it: `commonTest` sees `commonMain`'s `internal` declarations in this project, and
`commonTest/…/ImportSupportTest.kt:70` calls `internal fun inflateMaxOut` from `commonMain` today, so
that is the pattern and not a guess.

`Math.pow` was rejected: three calls per sample is real cost on the path JB-9.06 will drive per pixel,
and a `pow` is not bit-identical to the multiply, which would move the shipped paper by an ulp for no
reason.

### Why nothing existing can move

`1f·w` is exactly `w`, so the loop `g = 1f; g *= w` three times is `((w·w)·w)` — the same association, the
same rounding, the same Float, as the `w*w*w` it replaces. I checked 200 000 random float32 weights
through both: **0** differ. So `SampleSurface`'s accumulation is untouched and no existing assertion can
move.

### Derivation of every number in the new test

With `HEX_GAMMA = 3` and raw weights `(1/2, 1/4, 1/4)`:

```
w^3    = (1/8, 1/64, 1/64) = (8, 1, 1)/64
Σ w^3  = 10/64
w'     = (8/10, 1/10, 1/10) = (0.8, 0.1, 0.1)
```

`1/8` and `10/64` are both binary fractions (`2^-3` and `5·2^-7`), so the Float cubing and the Float
division are exact and the literals `0.8f` and `0.1f` are the same Floats the code produces — no
tolerance is needed. A gamma of 1 is the plain weights, because the raw weights sum to 1 exactly and
`w/1f = w`. A gamma of 0 is a flat `1/3` each, because every `g` is `1f` and the total is `3f`. Verified
in Python: `[0.8, 0.1, 0.1]`, `[0.5, 0.25, 0.25]`, `[1/3, 1/3, 1/3]`.

### The pin, and why it is the refusal and not a number

This is the part worth the Lead's attention. While `HEX_GAMMA` is 3, `w*w*w` and `w^HEX_GAMMA` produce
**identical bytes**, so no assertion about any blended value can tell them apart — not mine, not any
other, not a future one. The only thing that can tell them apart is a gamma that is not a whole number of
multiplies, which a hard-coded cube accepts silently and a read constant has to reject. That is why the
exponent is a parameter with the constant as its default, and why the test's real assertion is
`assertFailsWith { gammaWeights(raw, 2.5f) }`.

**Mutation reasoning:**

| Mutation | Which named test goes red |
|---|---|
| `gammaWeights` back to `out[k] = w[k]*w[k]*w[k]`, keeping the parameter | `theWeightContrastIsTheOneTheConstantNames` — the 2.5f call returns weights instead of throwing |
| drop the `require` from `gammaWeights` | same test, on the 2.5f call |
| drop the `gamma` parameter and hard-code `3f` in the body | the test will not compile, which is a red the orchestrator will see |
| `HEX_GAMMA` 3 → 5 | same test, on `assertEquals(3f, HEX_GAMMA)` and on the hand-derived (0.8, 0.1, 0.1) |
| normalise by the wrong total (e.g. divide by 3) | same test, on the hand-derived values |
| `gammaWeights` returns raw weights | same test, on gamma 3 (0.8 ≠ 0.5) and on gamma 1 |

---

## Finding 3 — the slope round trip is asymmetric · DISPUTED

This is the finding I disagree with, and the disagreement is the whole of it.

**The audit's own text says the fix already landed.** Finding 3 opens by naming JB-9.03b item 1 and the
new contract, then goes on to quote the numbers of the encoding that item replaced. The two halves come
from different snapshots. Checked against the landed file:

- `encodeSlope(0f, r)` is **127** (`SurfaceMaps.kt` post:100-104 → `round(127 + 127·0)`).
- `decodeSlope(127, r)` is **exactly 0f** (post:121 → `0/127f * r`).
- The audit's `decodeSlope(128, r) = +r/255` is the pre-JB-9.03b formula. The landed code gives
  **+r/127**, because `(128−127)/127f · r` — and `r/255` is simply the old number.
- A flat surface therefore does **not** read back with a bias. `ImportedTextureTest` asserts the flat
  board packs to `encodeSlope(0f, range)` (`:89-90`, `:230-231`), which is 127, which decodes to 0. The
  "+3.88e-4 per texel directional deposit" is `r/255`, i.e. the old layout, and there is no such
  deposit on the landed layout.

**Is there an asymmetry at all?** No. `encodeSlope(k·r/127, r)` is exactly `127 + k` for every integer
`k` in −127..127, because the encode is `round(127 + 127·clamp(k/127))` and `k` is already inside the
clamp. So byte `127 + k` names the slope `k·r/127`: byte 126 is `−r/127` and byte 128 is `+r/127`, which
are the two encode steps either side of flat, symmetric by construction, and `decodeSlope` is the exact
inverse of `encodeSlope` rather than an approximation of it. Byte 255 is never written; read one and it
decodes to `128r/127`, past the rail.

I checked all of that in Python: `encodeSlope(k·r/127) == 127 + k` for all 255 values of `k`, and the
six anchors (`127 → 0`, `126 → −r/127`, `128 → +r/127`, `0 → −r`, `254 → +r`, `255 → 128r/127`) come out
**bit-identical** in float32 to the way the test writes them.

### What changed anyway

A false finding still leaves the property unpinned, and the property is the one thing here worth a test.
New `SurfaceMapsTest.theSlopeByteLayoutHasNoHalfByteOffset` (post:182) asserts all 255 byte positions and
the six anchors, so the pre-JB-9.03b behaviour can never come back quietly — under that old encoding the
loop would fail at k = 0 (`encodeSlope(0)` was 128, not 127), and the anchor `decodeSlope(127) == 0` would
fail too. `decodeSlope`'s KDoc (post:109-118) now states the no-offset property and the 255 caveat.

### Derivation of the tolerance

The code computes `(k/127f)·r` while the grid value is `k·r/127`, and those two associations differ by up
to one ulp. One ulp of 0.099 is `7.45e-9`; I measured the worst disagreement over all 255 values as exactly
`7.45e-9`, one ulp. The loop therefore carries `1e-8`, about 1.3 ulps. The six anchors carry **no**
tolerance, because each side reduces to a value both sides hold exactly.

---

## Finding 4 — `A − B²` is not a variance · FIXED (the claim), DEFERRED (the consumer)

The audit is right, and I reproduced its numbers and then some.

### The arithmetic

With `b` the height byte, `u = b²/255`, `A = round(u)` (this is what `pack` writes, `SurfaceMaps.kt`
post:174) and `B² = b²/65025 = u/255`, so

```
A − B² = (round(u) − u)/255 = e/255,   e ∈ [−1/2, +1/2]
```

Put `r = b² mod 255`. Then `e` is `−r/255` when `r ≤ 127` and `(255 − r)/255` when `r ≥ 128`, so

```
A − B² = −r/65025          for b² mod 255 ≤ 127
A − B² = (255 − r)/65025   for b² mod 255 ≥ 128
```

- The bound is **`127/65025 = 1.95309e-3`**, tighter than the audit's `1/510 = 1.96078e-3`, which is the
  same bound without the mod. I verified the tighter bound holds for all 256 bytes.
- The **sign** is decided by `b² mod 255`, not by the surface. Neither sign means anything.
- Worked probes: `b = 1` → `u = 1/255`, `A = 0`, `A − B² = −1/65025 = −1.53787e-5`.
  `b = 12` → `u = 144/255`, `A = 1`, `A − B² = 111/65025 = +1.70704e-3`, which is 87.4% of the bound.
  These are neighbours on the same dark sheet, one "negative variance" and one "positive", and they differ
  by a single bit of A.

### What I measured on the shipped asset

The auditor measured 155902 of 262144 negative (59.5%), min −0.001861. I measured it myself with numpy on
`joybrush/assets/paper/surface_pulp_artisan.png`:

| | audit | mine |
|---|---|---|
| negative | 155902 of 262144 | **155902 of 262144** (59.472%) |
| minimum | −0.001861 | **−0.0018608227604768324** |
| maximum | — | +0.0018454440599769972 |
| `A == round(255·h²)` | "0 differing channels" | **262144 of 262144** |
| `|A − B²| ≤ 127/65025` | — | holds, max 0.0018608 |
| byte 255 in R or G | — | **never written**, 0 occurrences |

The minimum is not a coincidence: `−0.00186082276…` **is** `−121/65025`, which is `b² mod 255 = 121` at
`b = 11`, `A = 0`. The audit's figure and my arithmetic agree to the last digit.

### What changed

- `SurfaceMaps.pack`'s KDoc (post:139-159) replaces "A = h² gives the mips the surface's roughness for
  free, since variance = A − B²" with the arithmetic above, the asset figures, the instruction
  (`max(A − B², 0)`), and a line saying the bytes are unchanged and only the claim is corrected.
- `HexTile.gammaWeights`'s KDoc (post:194-197) replaces "A - B² has to stay a real variance, because the
  zoom maths in JB-9.06 reads roughness out of it" with the truth: a convex blend keeps A and B a valid
  mean and a valid second moment, and `A − B²` is still not a local variance.
- New `SurfaceMapsTest.theAlphaChannelIsAQuauntisedSecondMomentAndNotAVariance` (post:263) pins all 256
  bytes against the residue rule, so the rule cannot change under the test.

### The consumer is not this row's area

There is **no** `sqrt(A − B²)` anywhere in the repo today. `rg` for a variance or roughness read finds
`TuftStamp.kt:117` and `jb_tuft.frag:64`, which are `sqrt(h − b²)` on a tuft strand and a different thing
entirely. JB-9.06's zoom maths is not landed. So the `NaN` is a promise to a future row, not a live
crash, and the fix is to make the promise false-proof rather than to change bytes.

### Why the bytes are not changed

`joybrush/tools/paper/pack.py:41` writes `np.round(255 * h * h)` into A and `:40` writes
`np.round(127 + 127 * np.clip(s / slope_range, -1, 1))` into R and G. Any change to the layout would have
to land in pack.py, in the shipped PNG and in `jb_paper.glsl` in one commit, and would make the shipped
asset a different paper. That is a Lead decision, not an audit fix. See Questions.

### Mutation reasoning

| Mutation | Which named test goes red |
|---|---|
| A → `round(255·h)` instead of `round(255·h²)` | `theAlphaChannelIsAQuauntisedSecondMomentAndNotAVariance` on the `A` assertion for `b = 12` (1 against 128) |
| A → `(b*2).coerceAtMost(255)` | same, on `b = 12` |
| A's rounding changed from half-up to half-even | nothing here can catch it, and nothing can: `b²/255` is never at a .5 tie because `b²` is an integer and `255k + 127.5` is not |
| `A/255` used where the consumer wants a variance | not a mutation of this file; it is a consumer's problem, and the KDoc now says what to do |

The tie point is worth stating because it is why the Kotlin/Java and NumPy roundings cannot disagree here:
the fractional part of `b²/255` is a multiple of `1/255`, so the nearest `.5` is at least `0.5/255 = 1.96e-3`
away, and the float error is about `1e-15`. Verified: `round(255·(b/255)²) == round(b²/255)` for all 256
bytes.

---

## Finding 5 — a NaN slope packs as the opposite rail · FIXED

Confirmed, and the mechanism is as filed. `require(slopeRange…)` was there; nothing was checking `s`.

```
NaN.coerceIn(-1.0, 1.0)  ->  NaN, because `NaN < -1.0` and `NaN > 1.0` are both false
round(127.0 + 127.0 * NaN) ->  NaN
NaN.toInt()               ->  0            (JLS 5.1.3 narrowing: NaN -> 0)
byte 0                    ->  decodeSlope(0, r) = -r     the −range rail
```

`+∞` takes the other route: it clamps to 1 and packs as 254, `−∞` as 0. So all three non-finite values
were being written as a rail, with nothing thrown.

**What changed:** `require(s.isFinite())` at `SurfaceMaps.kt` post:102, message
`"a slope must be a finite number, was $s"`. The guard is on the value, not on the call, so it costs one
comparison on a path that is not the hot one — `pack` does not go through `encodeSlope`, it calls the
private `encodeDouble`, and its heights come from bytes so its slopes are finite by construction.

The audit's second claim also holds and I kept it in the KDoc: `defaultSlopeRange` does not always catch
it first. Its percentile index is `0.999·(n − 1)`; on a 512² field that is `0.999·524287 = 523762.71`, so
it reads indices 523762 and 523763 out of 0..524287, and twelve NaNs sorting to the end sit at 524276 and
above, never read. The range comes back a legal `0.001` and `pack` proceeds. On a small field the index
itself lands on a non-finite value, `percentile` is NaN, `coerceAtLeast(0.001f)` returns NaN (its
comparison is false for a NaN) and `require(slopeRange.isFinite())` throws. The same input, two different
symptoms, decided by texture size.

**Mutation reasoning:**

| Mutation | Which named test goes red |
|---|---|
| drop `require(s.isFinite())` | `aNonFiniteSlopeIsRefusedByName` — `assertFailsWith` finds no exception for NaN, +∞ or −∞ |
| check `s > 0f` instead of `isFinite()` | same — every negative legal slope would throw, and `aSlopeEncodesToItsByteWithTheHalfWayPointAt127` would go red first |
| reword the message so it does not contain `slope must be` | same test's message assertion; the `slopeRange` guard is passed a legal range in that test, so the two cannot be confused |

---

## Finding 6 — `hexTexels` had no guard · FIXED

Confirmed. `HexTile` guarded `out.size` (pre:110) and `slopeRange` (through `decodeFilteredSlope`), and
divided by `hexTexels` with nothing.

**What changed:** `require(hexTexels.isFinite() && hexTexels > 0.0)` at `HexTile.kt` post:80, in
`lattice`. That is the single place the division happens, and both `sampleSurface` (post:131) and
`sampleLook` (post:171) call `lattice` before anything else, so one guard covers all three entry points.
The message names the argument, as the `SurfaceMaps` guards do.

**One correction to the audit.** It says `hexTexels = NaN` "reaches `Double.toInt()` on a NaN and throws a
bare `IllegalArgumentException` with no message". It does not. JLS 5.1.3 narrowing is NaN → 0,
`+∞` → `Int.MAX_VALUE`, `−∞` → `Int.MIN_VALUE`, otherwise truncate; **none of those throw**. The failure
is silence, not an exception, which is worse and is what the guard is for.

What each bad value actually did before the guard, with `px = 3.0`, `py = 5.0` (ported to Python with
Kotlin's narrowing semantics, all four verified):

| `hexTexels` | `qx`, `qy` | `a`, `b` | hex indices | what the caller got |
|---|---|---|---|---|
| `0.0` | `+∞`, `+∞` | NaN, `+∞` | `(0, Int.MAX_VALUE)` | centres `× 0` = 0, `bilinear` wraps, a finite sample of the wrong patch |
| `−16.0` | −0.1875, −0.3125 | −0.00708, −0.36084 | `(−1, −1)` | nothing overflows at all; the lattice is quietly mirrored |
| `NaN` | NaN, NaN | NaN, NaN | `(0, 0)` | hexes around the origin, no complaint |
| `+∞` | 0.0, 0.0 | 0.0, 0.0 | `(0, 0)` | every point collapses onto hex (0, 0), forever |

The mirrored claim for `−16.0` is exact, not a figure of speech: `H = −16` at `(3, 5)` gives
`a = −0.007078041, b = −0.360843918, fa = 0.992921959, fb = 0.639156082`, which is bit-for-bit what
`H = +16` at `(−3, −5)` gives. Nothing throws, nothing looks wrong, and the weights still sum to 1.

**Mutation reasoning:**

| Mutation | Which named test goes red |
|---|---|
| drop the `require` from `lattice` | `aHexSizeThatCannotDivideIsRefusedByName` — no exception for any of the four values |
| check `hexTexels > 0` but not `isFinite()` | same, on `NaN` and `+∞` |
| reword so the message lacks `hexTexels` | same test's message assertion |
| guard `out.size` in `sampleSurface` but not `sampleLook` | same test, through its `sampleLook` call — which is why it asserts all three entry points |
| move the guard out of `lattice` into `sampleSurface` only | same test, on its `lattice` call and its `sampleLook` call |
| `hexTexels > 0.0` replaced by `hexTexels >= 0.0` | same test, on `0.0` |

**Checked that the guard cannot fire on a real caller.** `rg` for every `sampleSurface`, `sampleLook` and
`lattice` call site: `GrainMath.kt:83` (takes `hexTexels` from `SURFACE_HEX_TEXELS = 180f`,
`GrainMath.kt:48`), `PaperRaster.kt:24, 36, 74, 80` (from the catalogue entries, and
`PaperCatalogue.kt:152-153` already rejects anything outside `16f..size`), `PaperRaster.kt:22` (already
required positive), and every test passes a positive literal. No caller passes zero, a negative, a NaN or
an infinity.

---

## Finding 7 — "correlates 0.004" does not describe the shipped paper · PARTLY FIXED, DEFERRED for the board

The audit is right about the mismatch and right that it is not a repeat. Its numbers (hex read
autocorrelation at lag 512 of −0.0375 against a plain read's −0.0003; field-vs-field +0.947 on the asset)
are its own; I did not re-measure them, because the asset measurement needs the hex sampler in a loop and
I am not going to paste a number I did not produce into a test.

**What I could fix, did.** `HexTileTest.theSamePaperOneTexturePeriodAwayIsNotTheSamePaper`'s KDoc
(post:319-338) now says what the measurement is (grain at every scale, so nothing low-frequency for the
blend to line up with), why it is the right instrument (the plain-tiling control in the same test reads
1.000, so a small number means the hex read broke the repeat rather than that the instrument was blind),
and then says plainly that the 0.004 describes **this texture and not the property**, that the same
measurement on the shipped sheet reads about +0.95 because the sheet is smooth and low-frequency, and
that the tiling is still broken there because the hex read decorrelates faster along a line. It names the
auditor as the source of those asset figures and says they are not asserted here, because the asset is a
file and this test is commonTest.

**What is deferred.** The claim itself lives in `tasks/joybrush/ROADMAP.md:309`, which this row may not
touch, and a test that measures the asset has to be a `jvmTest` reading the PNG, which is also outside
this row's stated area. Both are in Questions for the Lead.

**Not weakened.** `theSamePaperOneTexturePeriodAwayIsNotTheSamePaper`'s bound is untouched: still
`correlation < 0.3`, still the exact-1.0 plain control at `1e-9`.

---

## Finding 8 — `worst <= 1` is too weak · DISPUTED (already fixed)

The audit quotes `SurfaceAssetTest.kt:72-76` asserting `worst <= 1` and 72-76 in a 72-line file. On the
landed branch the assertion is **`differing == 0`** (`SurfaceAssetTest.kt:61-65`).

```
git log --oneline -- joybrush/core/src/jvmTest/.../SurfaceAssetTest.kt
  b1b30633 JB-9.03b: correct paper slope twins and uploads for the owner
  6630f344 JB-9.01: turn paper height into slopes and the packed RGBA the GPU reads
git show 6630f344:...SurfaceAssetTest.kt   ->  assertTrue(worst <= 1, ...)
```

JB-9.03b Decision 2 said to assert 0 differing bytes and not to loosen the test, and it did. So the
`255f → 254f` mutation the audit describes cannot pass on this branch: it changes 262373 of 524288 slope
channels and `differing` is then nowhere near 0. The audit read the pre-JB-9.03b copy — the same
mixed-snapshot problem as finding 3, and the second time it has produced a finding about code that no
longer exists.

One residual from the finding is real and I did not touch it: `SurfaceAssetTest`'s `SLOPE_RANGE = 0.099f`
is a hard-coded copy of a shipped value (`joybrush/assets/paper/catalogue.json:11`, and
`GrainMath.kt:47` `SURFACE_SLOPE_RANGE = 0.099f` is a third). It is a `jvmTest`, it is outside this row's
area, and it is already filed separately in `reviews/JB-9.04_9.05__bunny-audit.md` finding 2. Noted in
Questions so it is not lost.

---

## Finding 9 — A has no signal over the darkest texels · CONFIRMED, not a bug

Confirmed as a description, and **the audit's wording is wrong**: it says A is "0 or 1" across "the 22
texels at B ≤ 11". It is exactly **0** at all 22. The band where A carries nothing is the twenty darkest
height bytes, and B ≤ 11 sits inside the A = 0 half of it:

```
A = round(255·(b/255)²) = round(b²/255)
A = 0   when  b² < 127.5   ->  b = 0..11
A = 1   when  127.5 ≤ b² < 382.5  ->  b = 12..19
A = 2   at b = 20  (400/255 = 1.5686)
```

Measured on the asset: B min is 1; 1 texel at B ≤ 1; **22** at B ≤ 11, all with A = 0; 109 at B ≤ 19,
with A in {0, 1}; 135 at B ≤ 20, with A in {0, 1, 2}.

**Is it a bug?** No. It is what one byte of `h²` does at the bottom of the range. At `b = 1`,
`255·(1/255)² = 0.0039`, which rounds to 0; `h²` has to fall below `1/510` before A leaves 0, i.e. `h`
below 0.044, i.e. `b` below 11.2. There is no rounding rule that would put a signal there — the values
being represented are smaller than half a byte. The only fixes are a different channel layout (`A = h`, or
a sqrt-domain A), and both change the shipped asset, pack.py and the shader.

**What changed:** the derivation is in `SurfaceMaps.pack`'s KDoc (post:151-154) and pinned for all 256
bytes in the new test, including the explicit `A = 0` for `b = 0..11`, `A = 1` for `b = 12..19` and
`A = 2` at `b = 20`. A consumer keying on `A == 0` to mean "dark and flat" is told, in the KDoc, that one
value means both a black texel and a rounded one.

---

## The audit's "not reproduced" gap — the rotation's SIGN · FIXED

The audit is exactly right that this is untestable by octant spread, and right that `R(+θ)` and `R(−θ)`
are mirror images with the same length and the same spread. I re-read both sides:

- CPU, `HexTile.kt` post:162: `contribution[1] = (-sn * sx + c * sy).toFloat()` — that is
  `R(−θ) = [[c, s], [−s, c]]` applied to `(sx, sy)`.
- Shader, `joybrush/shaders/jb_paper.glsl:60`: `slope = mat2(c, -s, s, c) * slope;`. GLSL `mat2` is
  column-major, so `mat2(c, −s, s, c)` has columns `(c, −s)` and `(s, c)`, i.e. the matrix
  `[[c, s], [−s, c]]`, which is the same `R(−θ)`.
- The forward rotation, `HexTile.kt` post:248 and `jb_paper.glsl:49` (`mat2(c, s, -s, c)`, columns
  `(c, s)` and `(−s, c)`, i.e. `[[c, −s], [s, c]]` = `R(+θ)`), is `R(+θ)` on both sides too.

So the two agree today and neither test could say so. (The audit cites the shader's lines as 53 and 44;
on the landed file they are 60 and 45. The content is what it says.)

### How the new test distinguishes them

`HexTileTest.theBackRotationTurnsTheSlopeBackTheSameWayTheReadWasTurned` (post:496). A texture whose
slope field points along **+x only** and whose size changes along x. At a hex centre the sampler reads
`slope_tex = (f, 0)` and then

```
R(-θ)·(f, 0) = ( cos θ · f , -sin θ · f )     <- the spec's maths block line 71
R(+θ)·(f, 0) = ( cos θ · f , +sin θ · f )
```

They agree on `dx` and disagree on `dy`, so `dy` alone is the discriminator, and a texture with no y-slope
at all is the cheapest thing that can show it. Sampling at a hex centre also makes `p − c` exactly
`(0, 0)`, so the forward rotation moves nothing and the texel the read lands on is `centre + offset`,
which the test can state exactly from `HexTile.centreX`, `HexTile.centreY` and `HexTile.hash`.
`HexTile.rotation` is private, so θ is rebuilt as `hash(i, j, 3)·2π`, the same expression; a change to the
hash channel it reads fails here rather than passing unnoticed.

### The texture, and every number derived

- 32 wide, 16 tall. R byte at `(x, y)` is `127 + m(x)` with `m(x) = min(x, 31 − x)`, so m runs
  `0, 1, …, 15, 15, …, 1, 0`. The wrap from x = 31 to x = 0 is `0 → 0`, so there is no jump at the seam.
- `encodeSlope(m·R/127, R) = round(127 + 127·m/127) = 127 + m` exactly for integer m in 0..15, and
  `127 + m ≤ 142`, so nothing clamps. This is finding 3's rule, reused.
- G is `encodeSlope(0, R) = 127`. `decodeFilteredSlope` maps byte 127 to exactly 0: `v·255f − 127f` for a
  Float `v` that is the correctly rounded `127/255` is at most `255·ulp(0.498)/2 + ulp(127)/2 = 7.6e-6`,
  which is inside the `1/65536 = 1.526e-5` alias window. So the texture really has no y-slope.
- m is affine on texel pairs `[0, 15]` and `[16, 31]` and flat across `[15, 16]` (m(15) = m(16) = 15), so
  the read must not land there. Filter: `x0 = floor(t.x − 0.5)` in `[8, 14] ∪ [16, 22]`, which puts the
  interpolated m in `[8, 15]` and so `f ∈ [8R/127, 15R/127] = [0.031496, 0.059055]` at `R = 0.5`.
- Second filter `|sin θ| ≥ 0.5`, which holds for 2/3 of a uniform hash (`u ∈ [1/12, 5/12] ∪ [7/12, 11/12]`).
- Together they keep `14/32 × 2/3 = 0.29` of hexes. Over the 96 hexes scanned (`i` in 0..11, `j` in 0..7)
  that is 28 in expectation with a spread of `sqrt(96·0.29·0.71) = 4.4`, so the assertion asks for **8**,
  about 4.5 spreads below.
- **The gap.** A flipped sign puts the answer `2·sin θ·f` away, at least `2·0.5·8·0.5/127 = 0.031496` with
  both filters. The tolerance is **1e-6**, so a flipped sign lands 31 496 times outside it.

I ported the whole of `sampleSurface` (lowbias32 hash, the shear, the gamma, the wrapping bilinear, the
filtered decode, both rotations) to Python and ran the proposed test against it:

```
hexes scanned 96            min lattice top weight at a hex centre: 0.9999999999999991   (filter 0.999, all pass)
hexes passing the filters: 27   (expectation 28, spread 4.4)
worst |out - predicted| over dx and dy at every hex: 0.0        (tolerance 1e-6)
smallest sign-flip gap 2*|sin*f|: 0.034898746646612445   ->  34 899 x the tolerance
of those 27, how many a R(+theta) back-rotation would fail: 27 of 27
```

Note the prediction is **exact**, not within a tolerance: at a hex centre the lattice weights are
`(1.0f, 0.0f, 0.0f)` after the gamma, because the raw weight `1 − 1e-16` rounds to `1.0f` in Float and the
other two are `1e-48f`, below Float's smallest subnormal `1.4e-45`, so they flush to `0.0f`. That holds for
either branch of the `fa + fb > 1` test, because hex `(i, j)` is the dominant vertex in both.

### Mutation reasoning

| Mutation | Which named test goes red | By how much |
|---|---|---|
| `R(−θ)` → `R(+θ)` at `HexTile.kt` post:162 | `theBackRotationTurnsTheSlopeBackTheSameWayTheReadWasTurned` | 27 of 27 hexes, by ≥ 0.0315 against a tolerance of 1e-6 |
| drop the back-rotation entirely | `theBackRotationTurnsTheSlopeBackTheSameWayTheReadWasTurned` **and** `aRotatablePaperTurnsEachHexsSlopeAndKeepsItsLength` | the first on all 27; the second on the octant spread, 1 of 8 |
| forward rotation `R(+θ)` → `R(−θ)` in `readAt` | neither of these two: the pair composes to the identity either way. `PaperRasterTest.localFramePreservesGlobalHashAndSamplesAtLargeSignedCoordinates` compares `HexTile` against `PaperRaster`, and both would need to move together. **This is a real remaining gap and it is the Lead's.** |
| `hash(i, j, 3 + seed)` → a different channel in `rotation` | the sign test (θ no longer matches), and the octant spread |
| `rotatable` ignored in `sampleSurface` | the sign test (27 of 27) |

I have written that third row down rather than leaving it implied. A test cannot pin the forward
rotation's sign on its own, because `readAt` and `sampleSurface` compose to the identity whichever way
each of them turns. Only the shader breaks that tie, and the shader needs a GPU.

---

## Symbols checked, and where I read them

Read in this worktree at `cd726f0b` unless marked otherwise.

**Owner area, `commonMain`**
- `HexTile.kt` pre:25 / post:35 — `const val HEX_GAMMA = 3f`, KDoc "Weight contrast: `w^3`"
- `HexTile.kt` pre:59-86 / post:79-108 — `lattice`, the shear and the `fa + fb > 1` test
- `HexTile.kt` pre:89-91 / post:119,122 — `centreX`, `centreY`
- `HexTile.kt` pre:100-137 / post:131-168 — `sampleSurface`, including pre:110 `require(out.size >= 4)`
- `HexTile.kt` pre:120-121 / post:151-152 — the two `decodeFilteredSlope` calls (the KDoc at pre:97 named
  `decodeSlope`, which was false; corrected at post:127)
- `HexTile.kt` pre:129-130 / post:161-162 — the `R(−θ)` back-rotation
- `HexTile.kt` pre:140-150 / post:171-180 — `sampleLook`
- `HexTile.kt` pre:152-155 / post:182-201 — `gammaWeights`' KDoc and its "A - B² has to stay a real
  variance"
- `HexTile.kt` pre:157-166 / post:202-217 — `gammaWeights`, `out[k] = w[k] * w[k] * w[k]`
- `HexTile.kt` pre:173-198 / post:224-248 — `readAt`, including pre:182-183 the `centreX`/`centreY` calls
- `HexTile.kt` pre:201 / post:252 — `rotation(i, j, seed) = hash(i, j, 3 + seed) * TAU`
- `HexTile.kt` pre:40-50 / post:50-60 — `hash`, and the `shr`-on-`UInt` KDoc
- `HexTile.kt` pre:27-30 — `SQRT3`, `TAU`
- `SurfaceMaps.kt` pre:85-91 / post:100-104 — `encodeSlope`
- `SurfaceMaps.kt` pre:93-94 / post:106-107 — `encodeDouble`
- `SurfaceMaps.kt` pre:96-100 / post:109-122 — `decodeSlope`
- `SurfaceMaps.kt` pre:103-106 / post:125-128 — `decodeFilteredSlope`, and the `1f/65536f` alias window
- `SurfaceMaps.kt` pre:74-85 — `defaultSlopeRange`, and the `0.999·(n − 1)` percentile index
- `SurfaceMaps.kt` pre:108-117 / post:130-160 — `pack`'s KDoc, including pre:116 the false variance claim
- `SurfaceMaps.kt` pre:118-131 / post:161-177 — `pack`'s body, including pre:125-126 the per-texel
  `slopeRange.toString().toDouble()`
- `SurfaceMaps.kt` pre:37-64 — `slopes`, `slopesDouble`, the 3,10,3 kernel and `/32`

**Owner area, `commonTest`**
- `HexTileTest.kt` pre:18-35, 37-48, 54-64, 69-109, 113-127, 131-170, 174-191, 195-249, 253-320, 324-347
- `SurfaceMapsTest.kt` pre:24-29, 31-96, 100-118, 122-142, 147-170, 173-196, 200-204

**Read for context, not edited**
- `SurfaceAssetTest.kt:31-71` — post-JB-9.03b, `differing == 0` at :61-65; `git show 6630f344` for the
  `worst <= 1` version the audit quotes
- `PaperRaster.kt:22-28` (`localFrame`, `centreX`/`centreY` at :26), `:32-57` (`sampleLocal`,
  `centreX`/`centreY` at :44, and the **third** hard-coded `w*w*w` at :37), `:74,80` (the sampler calls)
- `PaperRasterTest.kt:46-61`, especially the `abs(f.localOriginX) < 64` bound at :50 — the test that goes
  red under the finding-1 mutation
- `PaperTexture.kt:26-51` — `bilinear`, the `tx − 0.5` half-texel convention and the double weights
- `PaperCatalogue.kt:27, 46, 152-153` — `hexTexels` is documented as 16..size and validated as such
- `ImportedTextureTest.kt:49-50, 89-90, 216-231` — the flat-board case; it already asserts
  `encodeSlope(0f, range)`, not a literal 128, so JB-9.03b fixed it
- `GrainMath.kt:47-48, 78-87` — `SURFACE_SLOPE_RANGE = 0.099f`, `SURFACE_HEX_TEXELS = 180f`, the sampler
  call, and that `paperCoarseHeight` reads only `sampled[2]`, never the A channel
- `ImportSupport.kt:105-106` and `commonTest/…/ImportSupportTest.kt:70` — the precedent that `commonTest`
  sees `commonMain`'s `internal` declarations, which is what lets `gammaWeights` be tested
- `joybrush/tools/paper/pack.py:4, 40, 41` — the Python twin's encode and its A channel
- `joybrush/assets/paper/catalogue.json:11, 12` — `slopeRange: 0.099`, `hexTexels: 180`
- `joybrush/shaders/jb_paper.glsl:22-24, 45, 49, 60` — `SQRT3`, the shear, the centre, the forward
  rotation, the back-rotation. **Read only. Not edited.**
- `tasks/joybrush/specs/JB-9.02_hex_tile_sampler.md:30, 66, 71, 72, 78, 84-94`
- `tasks/joybrush/specs/JB-9.01_surface_maps.md:38-40, 54, 62-79`
- `tasks/joybrush/specs/JB-9.03b_paper_audit_fixes.md:17-25, 54` and `:19-20` for the new encoding
- `tasks/joybrush/ROADMAP.md:308-309` — the board rows, read not touched
- `git log --oneline -- SurfaceAssetTest.kt` → `b1b30633`, `6630f344`
- `git log --oneline -- SurfaceMaps.kt` → `b1b30633`, `6630f344`

**In the owner's folder, read only (R43):** `tasks/joybrush/reviews/JB-9.01_9.02__bunny-audit.md`, and
`SurfaceAssetTest.kt` to compare its uncommitted state against this branch's HEAD.

**Python, not Gradle.** Seven ports of the same maths, all run against this branch's code:
the shipped asset's A and B channels; the closed-form centre values; the gamma weights; the whole of
`sampleSurface` for the rotation-sign test; the encode/decode byte grid; the `A − B²` residue rule over all
256 bytes; the `lattice` behaviour for the four bad `hexTexels` values with Kotlin's narrowing semantics;
and `localFrame` for the finding-1 mutation. Nothing under Gradle was run.

---

## One thing changed that is not a finding

`SurfaceMaps.pack` built `slopeRange.toString().toDouble()` twice per texel, inside the loop. The same
expression is now built once, before the loop. It is the same value — `Float.toString().toDouble()` is
deterministic and side-effect free — so no byte can move, and on a 512² surface it removes 524 288 string
round-trips per pack. It is in the report because the rule is that an expected value only changes with its
derivation, and this one does not change at all. Say so if you would rather I had left it.

---

## What is still unproven

Everything below is unproven because nothing was executed.

1. **No test in this branch has been run.** Not one. Every "goes red" in this report is reasoning over the
   code plus a Python port, which is strong evidence about the assertions and is not the build's output.
2. **The Python port is not the JVM.** It agrees with the landed code on everything I could check
   independently, and I checked the asset figures, the hash, the shear, the gamma, the wrapping bilinear,
   the filtered decode, the encode/decode grid and the narrowing semantics. But Kotlin's `Float`/`Double`
   arithmetic, `Math.cos`/`Math.sin`, `Float.toString()` and `kotlin.math.round` are not the same
   functions. The places where that could bite:
   - the sign test's tolerance of `1e-6` on a predicted error of exactly `0.0` in the port. If the JVM's
     `cos`/`sin` differ from the port's by even a few ulps the prediction still moves with them (the test
     calls the same functions), so this should be safe, but I cannot show it.
   - `theHexCentreIsThePointTheSpecWroteDown`'s `1e-13` on the y values, derived from three roundings and
     a literal. The port's worst was 2 ulps at magnitude 43.3.
   - `gammaWeights`'s bit-identity with the old `w*w*w`, argued from `1f·w == w` and checked over 200 000
     random float32 weights in numpy.
3. **`gammaWeights` being `internal` and visible from `commonTest`.** The precedent
   (`ImportSupportTest` calling `internal fun inflateMaxOut`) is in this module and this source set, so I
   expect it to compile. If Kotlin's friend-module setup differs for a member of an `object`, this branch
   will not compile and the fix is to widen it to `public`.
4. **`theAlphaChannelIsAQuauntisedSecondMomentAndNotAVariance` at 256 iterations of `pack` over a 16×16
   field.** That is 256 texels, so it is trivial work; I have not run it, and I have not confirmed the
   assertion count reads the way I expect.
5. **The sign test's hex-count floor of 8.** The port says 27 pass the filters, against an expectation of
   28 with a spread of 4.4. The floor is 4.5 spreads below the mean. If `PaperTexture`'s half-texel
   convention ever moves, the `x0` filter selects the wrong band and this test goes red for the wrong
   reason. The convention is pinned by `aFilteredSlopeIsDecodedWithoutByteRounding`, so a move there is
   already someone's red.
6. **The asset figures in the KDoc** (155902 of 262144, −1.8608e-3) are mine, measured with numpy on the
   shipped PNG. They are a statement in a comment, not an assertion, so nothing checks them; if the asset
   is ever regenerated they will be stale and no test will say so.
7. **The forward rotation's sign is still unpinned**, as set out in the sign test's mutation table. Both
   sides of the twin compose to the identity either way, so only the GPU can break that tie.

---

## Questions for the Lead

1. **The shader's copy of the hex centre.** `joybrush/shaders/jb_paper.glsl:45` carries
   `u_paperHexTexels * vec2(float(i) + float(j)/2.0, float(j)*SQRT3/2.0)` inline. I have pinned the Kotlin
   side (`theHexCentreIsThePointTheSpecWroteDown` plus test 4 and test 7 now calling `centreX`/`centreY`)
   and `HexTile.centreX`'s KDoc names the shader line. I have **not** edited the shader — it is a lead-only
   hot file. Requested: either a check in `shader_check.js` that reads the centre out of a hex at a known
   `(i, j)` and compares it against the values in that test, or a line in the shader naming
   `HexTile.centreX` as the contract. Without one of those the GPU half of the twin is unpinned, which is
   the same gap the audit filed, one level down.
2. **The forward rotation's sign.** Not fixable in either row's tests, as the sign test's mutation table
   says: `readAt`'s `R(+θ)` and `sampleSurface`'s `R(−θ)` compose to the identity whichever way each turns.
   `jb_paper.glsl:49` is `mat2(c, s, -s, c)` = `R(+θ)`, which matches `HexTile.kt:248`, but only a GPU can
   say so. Requested: a `shader_check.js` case that samples a texture with a slope field that varies along
   x and checks the returned slope vector's direction, the way the new CPU test does.
3. **The board's "correlates 0.004"**, `tasks/joybrush/ROADMAP.md:309`. The number describes a 32×32
   random-byte texture, not the shipped paper; on `surface_pulp_artisan.png` the same measurement reads
   about +0.95 because the sheet is smooth and low-frequency. The tiling is still broken there (the hex
   read's line autocorrelation at lag 512 is about −0.0375 against a plain read's −0.0003), so this is a
   wording fix and not a defect. I have put the correction in the test's KDoc and left the board alone.
   Requested: reword the row to name the texture the number came from, or replace it with the
   autocorrelation figure, which is the one that describes the property.
4. **A test that measures the asset for finding 7** would have to be a `jvmTest` reading the PNG, which is
   outside this row's stated area. Requested: a line assigning it, or a decision that the KDoc correction
   is enough.
5. **`A − B²` has no consumer yet, but JB-9.06 is named for one.** When JB-9.06 lands, the zoom maths must
   take `max(A − B², 0)` before any `sqrt`. The arithmetic and the instruction are now in
   `SurfaceMaps.pack`'s KDoc, and `theAlphaChannelIsAQuauntisedSecondMomentAndNotAVariance` shows the
   `NaN` on a real byte. Changing the layout instead (a sqrt-domain A, or `A = h`) is possible but lands
   pack.py, the shipped PNG and the shader in one commit, and it is a Lead decision.
6. **`PaperRaster.kt:37` is a third copy of the gamma.** `FloatArray(3) { lattice.w[it] * lattice.w[it] *
   lattice.w[it] }`, renormalised by `weights.sum()` — the same maths as `HexTile.gammaWeights`, written
   out again, with no constant. The audit did not find this one. `PaperRaster.kt` is not in this row's
   area so I have not touched it. Requested: `HexTile.gammaWeights` is now `internal` and reachable from
   `commonMain`, so `sampleLocal` can call it and the two can never drift. That is a one-line change in a
   file I was not given.
7. **`SurfaceAssetTest.SLOPE_RANGE = 0.099f`** is a hard-coded copy of `catalogue.json`'s `slopeRange`, and
   `GrainMath.SURFACE_SLOPE_RANGE = 0.099f` is a third. The audit filed this for JB-9.04/9.05; this row
   found it independently. All three are `jvmTest` or `commonMain` outside this area. Noted so it is not
   filed twice.
8. **The audit was read from the owner's folder, not from this worktree**, and it is a mixed snapshot: its
   findings 3 and 8 both quote the pre-JB-9.03b encoding while finding 3 names JB-9.03b as having landed
   the fix. Findings 1, 3, 6 and 8 all needed re-deriving against `cd726f0b` before I could act on them.
   If the audit is meant to be re-run, it should be pointed at a named commit.
