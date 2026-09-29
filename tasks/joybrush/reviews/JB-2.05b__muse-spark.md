# Adversarial review — JB-2.05b Transform maths (homography + resample)

- Reviewer: muse-spark (cross-reviewer; builder was an unrelated subagent — different family).
- Task status: 🟧 Built. Commit reviewed: `74b5195d`.
- Spec reviewed: `tasks/joybrush/specs/JB-2.05b_transform_and_resample.md` (contract, decisions 1–5, tests 1–7 + builder Questions 1–3).
- §5b checks: diff touches only NEW `select/Homography.kt`, NEW `select/Resample.kt`, NEW `HomographyTest.kt` + `ResampleTest.kt`, board row, spec questions — inside the owner area; no handles/touch/GL per "Do not". Suite run by me in a clean HEAD worktree: `HomographyTest` 25/25 + `ResampleTest` 14/14, 0 failures (full `:core:jvmTest` 584/3, the 3 being JB-3.08a WIP reds + JB-5.10 flaky timing — none here). The builder's Python-model diffing discipline (two self-found bugs) is confirmed in the commit message, not just claimed.
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Finding 1 (BLOCKER): `Resample.over` is destination-over with per-channel alpha — not source-over; every composite is wrong
Proof: `Resample.kt:302` — `tile[at+c] = (bv + (tv·(255−bv)+127)/255)` per channel `c`, i.e. `out = bottom + top·(1−bottom)` with the factor taken **per channel** from `bottom`'s own value. Correct premultiplied source-over is `out = top + bottom·(1−top_alpha)` with **one** factor from `top`'s alpha. Consequences, all pinned by the suite's own assertions against the wrong values: opaque blue over half-red keeps red 128 (correct 0); opaque black over opaque grey returns the *bottom* (correct top); `tv==0 → b` ignores `top_alpha` entirely (semi-transparent black over white returns white, correct ~127 grey). The doc claim (`Resample.kt:263-264`, "ONE, ONE_MINUS_SRC_ALPHA… GPU has") contradicts the code — the code is `ONE_MINUS_DST(per-channel), ONE`: swapped src/dst AND wrong factor channel. This also invalidates Decision 5 under either formula (see below) and affects every `over` caller. Fix swaps the operands and the factor; the tests asserting current values will need re-derivation, not just re-approval.

## Spec finding (for the Lead, not the code): Decision 5's `±1` is arithmetically impossible over source-over — Q1 is proved right
Proof: worked, not estimated — `v=255`, coverage 128: `lifted = 128`, `remaining = 127`, composite `127 + round(128·128/255) = 127+64 = 191`, **64 short** (alpha identically). The suite asserts exactly 191, so the gap is a red test, not a comment. Exact only for 0/255 masks. The invariant that *does* hold with no rounding anywhere is `lifted + remaining == v` per channel (asserted). Ruling needed as Q1 asks: composite should be clamped ADD (exact), or Decision 5 must say "exact at 0/255, darker where soft" — a caller reading `±1` ships a soft selection that visibly fades. (With Finding 1 fixed, re-verify which formula the ruling picks — the 191 derivation assumes a correct source-over.)

## Finding 2 (MINOR): `then` kdoc states the composition order backwards
Proof: `Homography.kt:69-71` says `translate(10,0).then(scale(2,2))` "scales first"; implementation (`then(next) = mul(next.m, m)`) + `HomographyTest:186-198` (`(1,1) → (22,2)`, translate-first) prove translate-first. Code and test right, comment wrong — misleads every future composition. One-line doc fix.

## Finding 3 (MINOR): Q3 misstates its own minification threshold
Proof: code (`Resample.kt:203`, `MINIFY_SIGMA=2.0`) takes the 9-sample path on `span > 2` (spec Decision 3 "more than 2" — code right); the spec's Q3 note claims "2.0 exactly takes the nine-sample path" — it does not. Same class of undisclosed-threshold issue as `DET_EPS/AREA_EPS/PIVOT_EPS/AFFINE_EPS`, which Q3 should have listed but didn't. Comment-only fix.

## Verified (proof — the maths was re-derived, not just test-counted)
- `fromQuads`: 8-unknown solve (`h33=1`) with partial-pivot Gaussian elimination, all 8 equations checked term by term; collinear-triple + pivot-fallback double refusal; bow-tie explicitly not refused (pinned) per Decision 1.
- `inverse`: adjugate transposed verified term by term (all 9 cofactors); `then` order proved by the `(22,2)` test; integer-translate byte-identity and 90°-about-pixel-centres NEAREST exactness re-derived through the `(x+0.5)` centres (no off-by-half-pixel).
- `warp` walks the INVERSE (`h.inverse()` once, per-dest-centre mapping) — the forward-mapping hole bug class is structurally absent; dest span from mapped corners of non-empty tiles with `floor().toInt()` (negative-safe); pole-inside-tile limitation honestly documented; `ADDRESS_LIMIT=1e9` pre-`toInt` guards are correct hardening.
- Minification direction correct (Jacobian of the *inverse* = src-per-dest; discriminant clamped so uniform scale's exact-0 takes the averaging path — the builder's self-found `sampleSpan` bug class, confirmed fixed).
- BILINEAR on premultiplied channels with transparent-outside and single rounding; all-zero tiles never returned; inputs never modified; `isAffine` literal to contract (the `m[8]==0` hole is inherited from the contract text, code-faithful — flagged, not filed).
- Q2 confirmed and endorsed: all three refusals (singular, horizon corner, span cap) return the same empty map as empty source — a caller that must show "too big to move" needs a separate signal (thrown/nullable); contract gives none.

## Recommendation
Fix Finding 1 first (it poisons every `over` result and the Decision-5 ruling depends on the corrected operator); the Lead ruling on Decision 5 (ADD vs source-over vs reword) is the other half of the same fix. 2–3 are comment-only MINORs.

## Addendum 2026-09-29 — BLOCKER fixed (`6a9630fa`), verified
- Finding 1 fixed: `over` is now `top + bottom·(1−top_alpha/255)` with ONE factor from the source's alpha (plus KDoc rewritten to state both halves: the drawn thing is the source, the factor is the source's alpha). Verified in the diff, not just the message. The builder modelled integer, GL-literal-float, and exact-rational implementations over a million pixel pairs pre-fix (zero disagreements), and the three source-over identities are now pinned as tests.
- Decision-5 arithmetic update: the 191 derivation now goes through the CORRECTED operator and the number is unchanged (128+127 splits to 191 under either formula — coincidence, confirmed). The Lead's open question stands exactly as before, still red by 64; correctly left red rather than made green.
- Fresh run in a clean HEAD worktree: `ResampleTest` 16/16 (14 old + 2 new), `HomographyTest` 25/25, 0 failures. Finding 1 closed; spec ruling + Findings 2–3 (MINOR) stand.
