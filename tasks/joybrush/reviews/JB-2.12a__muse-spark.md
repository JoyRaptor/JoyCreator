# Adversarial review — JB-2.12a Guides + stroke snapping (the maths)

- Reviewer: muse-spark (cross-reviewer; builder was an unrelated subagent — different family; orchestrator found+fixed the Newton bug).
- Task status: 🟧 Built. Commit reviewed: `22ac5ffa` (guide half; selection half is JB-2.05a, reviewed separately).
- Spec reviewed: `tasks/joybrush/specs/JB-2.12a_guides_and_snapping.md` (contract, decisions 1–7, tests 1–9 + orchestrator's Newton post-mortem + Q1–Q8).
- §5b checks: diff touches only NEW `guide/Guide.kt`, `GuideSnapper.kt`, `GuideLines.kt`, NEW guide tests, spec questions — inside the owner area; no existing file touched; guides never written into tiles/exports. Suite run by me in a clean HEAD worktree: `GuideSnapperTest` 10/10 + `GuideLinesTest` 10/10, 0 failures. The orchestrator's Newton writeup (wrong fix → instrument → true `D″`) is confirmed present-and-correct in code (`f2 = 2·((ry²−rx²)(cos²t−sin²t) + rx·u·cos t + ry·v·sin t)`), verified term-by-term against `D(t)` — not taken on trust.
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Finding 1 (MAJOR): perspective snap direction is measured at the current point, not the start — the locked line through S points the wrong way
Proof: `GuideSnapper.kt:118` — `nearestDirection(mx, my)` calls `candidates(mx+sx, my+sy)`, i.e. the CURRENT point, while `candidates(x, y)` (`:134`) documents "the stroke's start" and the VP branch (`:151-155`) computes `VP − (x,y)`. Spec decisions 1–2: candidates through S, projection onto the line through S. Grid/Iso directions are position-independent so the bug hides; for Perspective the locked `dir` is `VP−current` but `ontoLine` draws through S — error ≈ `lockDist / dist(S,VP)` (≈0.7° at 12 px/1000 px, ≈9 px miss at the end of a 1000 px stroke: visible for a drawing aid). Hidden because spec test 5 walks exactly along `S→VP` (collinear ⇒ error 0). Fix: `candidates(sx, sy)`.

## Finding 2 (MINOR): non-finite ellipse `center` fails OPEN — locked highlight, stroke never moves
Proof: `ellipseDistance` (`:224-228`) guards only `rx/ry`, not `center/rotation`; `ellipsePoint` (`:256-257`) returns the input point for a bad center, so distance is `hypot(0,0) = 0 ≤ REACH/k` → locks with `isLocked=true`, but `ontoTracer` never moves anything. (NaN rotation is fail-closed via NaN distance; only center fails open.) Same one-line class as the ruler case: return `MAX_VALUE` like degenerate radii (and like `rulerDistance:213` already does).

## Finding 3 (MINOR): degenerate `Ruler(a==b)` locks but cannot move — misleading UI highlight
Proof: distance (`:219`) returns distance-to-`a` (can lock), projection (`:243-244`) returns identity (never moves), overlay (`GuideLines.kt:211`) draws nothing. Result `locked=true` + untouched stroke. Should be `MAX_VALUE` in the snapper to match the overlay. Same fix shape as Finding 2.

## Finding 4 (MINOR): stale curvature comment contradicts the fix it sits above
Proof: `GuideSnapper.kt:252-254` ("squared distance… concave… second derivative always negative") is sign-inverted vs the correct code + long comment at `:270-284` (positive at the minimum, collapsing to `2(ry²+rx²·sin²)` on-curve). Pre-fix leftover; will mislead the next editor of exactly the function that already cost two wrong fixes. Delete or flip the sentence.

## Verified (proof)
- Lock mechanics: 12 screen-px gate (`hypot·k`, doc→screen correct), `>20°` gives up (so exactly 20° still locks — matches "more than 20°"), `gaveUp` latch, lock decided on the deciding sample, pre-lock samples byte-identical.
- Tracer reach 24 screen px (`> REACH/k`, correct direction), decided once at stroke start, nearest-tracer-wins, tracer-beats-direction; ruler = infinite-line distance + orthogonal projection (both re-derived).
- `map` changes x/y only (`s.copy(x,y)`) — pressure/tilt/azimuth/barrel/time/tool AND `predicted` preserved (Q7); non-finite sample returned unchanged without poisoning `hasStart`.
- GuideLines: thinning doubles to ≥8 screen px with the Inf guard; 2000 cap by stopping (index range from the view extent, never allocated-then-truncated — Q4); perspective 24 rays over full 360° + horizon through first two points (Q2); ruler extended across view; ellipse fixed 128 unclipped closed polyline (Q1 — overlay must not assume in-view-only for this guide); corner-touch lines yield no segment (the 13+11+13 count, Q3); `locked` true from the deciding sample (Q5); `viewDoc` built by the JB-2.12 overlay, nothing added to `ViewTransform` (Q6); tile size never read (Q8).
- Empty `Perspective([])` offering vertical+horizontal in the snapper while the overlay draws nothing: inconsistent but spec-silent (1–3 points only) and harmless — noted, not filed.

## Recommendation
Fix Finding 1 (systematic small-angle error on exactly the guide type that needs long accurate lines); 2–4 are MINOR. The Newton lesson in the spec ("instrument, don't reason") is now load-bearing project lore — keep it.

## Addendum 2026-09-29 — Finding 1 fixed (`b8dd2ea9`), verified
- Fixed as prescribed (`candidates(sx, sy)` — direction through S), measured 0.245°/4.269 px pre-fix, exactly `lockDist/dist(S,VP)` as derived. The fix also closed a second silent fail-open my review missed: a VP sitting exactly on the start offered a 0.0003°-off candidate that locked and projected the identity (highlight with no effect) — now offers no candidate. Perspective test strengthened to assert the WORST residual over the stroke (the deciding sample was only 0.057 px out under the bug — too thin to pin a broken fix; the end is 4.27 px).
- Fresh run in a clean HEAD worktree: `GuideSnapperTest` 16/16 (10 old + 6 new), `GuideLinesTest` 10/10, 0 failures. Finding 1 closed; Findings 2–4 (MINOR) stand.
