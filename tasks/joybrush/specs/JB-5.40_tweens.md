# JB-5.40 — Tweens: a held frame slides its lines into the next frame (R51 Phase 4)

| | |
|---|---|
| **Tier** | T1 design by the Lead; 5.40a is T2 and dispatchable now |
| **Status** | 📝 Design, Lead 2026-10-08 |
| **Needs** | 5.40a: nothing (pure core on `StrokeRecord`s). 5.40b: JB-5.20a. 5.40c: JB-5.20e and the board frame strip |
| **Reads** | `design/ONE_LAYER_MODEL_BRIEF_20261007.md` §9 (the owner's own note under it); JB-5.20 D15; JB-3.00a (frames are the board's region) |

## The owner's words, verbatim (brief §9, his note on idea 2)

> *"2 expanded: tweens. yes!*
> *1. duplicate an animation frame with the new frame button.*
> *2. move the vector lines in the second frame, nudge the vectors, change there color*
> *3. up the timing of the first frame so that it is greater then 1 and tap the "tween" toggle - its frame changes color
> in the bottom scrubber bar - every vertext and every thing that continues to exits in the second frame gets an
> interpoliation, transforms like rotation rotate around there pivot, line paths recurve from a to be, color hsb slides
> from one to another etc."*

## Decisions

**T1 — A tween belongs to a frame.** Frame A with `holdFrames = n > 1` and its tween toggle on shows, for its n ticks,
A at t = 0 and then n − 1 in-betweens at t = k/n, sliding toward the NEXT frame B in play order (the last frame tweens
toward the first only when the board loops). B itself is drawn as it is. In-betweens are generated, never stored and never
saved; export draws them the same way. Toggling a tween, or changing a hold, is one undo step.

**T2 — Lines are matched by id.** A duplicated frame keeps every line's id (JB-5.20 D15), so frame B's line `a` is frame
A's line `a`, edited. Only matched lines interpolate ("every thing that continues to exist in the second frame"). A line
only in A is drawn unchanged until B; a line only in B appears at B. No fades in this row: predictable first; a fade
option can come later.

**T3 — Shape: rigid motion first, then the bend.** For a matched pair, the best similarity transform from A to B
(rotation, uniform scale, translation; least squares on the resampled points, Kabsch/Umeyama) is interpolated as a
transform: the angle and the log of the scale linearly, about the pivot. What is left after that transform (the nudge,
the recurve) is interpolated point by point. So a rotated line turns round its pivot instead of shrinking through the
middle, and a nudged line bends from A to B.

**T4 — The pivot of a group.** Lines the owner moved together (a selection rotated as one) share a pivot. Matched lines
whose best transforms agree (angle within 0.5°, scale within 0.5%, and the same transform carrying each line's centroid
within 0.5 doc px) form a group, and the group turns round the group's own centre of rotation. A line alone turns round
the centre its own transform implies. Grouping is deterministic: lines sorted by id, groups grown in that order.

**T5 — Points are matched by arc length.** When the two lines have different sample counts (a slice, an erase, a redraw),
both are resampled to the larger count at equal arc-length fractions, keeping each line's own ends. Pressure and tilt are
interpolated per point like positions. Azimuth and barrel are document-space angles: they turn with the rigid rotation
of T3, then the remainder is interpolated the short way round (a NaN channel stays NaN). Width scale is interpolated. Timing values are
A's.

**T6 — Colour slides in HSB**, hue the shorter way round, saturation, brightness and alpha linearly (owner: "color hsb
slides"). A grey end (saturation 0) takes the other end's hue, so a grey-to-red slide does not swing through green.

**T7 — What does not interpolate.** A brush swap between A and B shows A's brush until B. Pixels (slabs) do not
interpolate: A's pixels are shown through A's ticks and B's at B. Fills (filled lines) interpolate their outline like
any line, and their colour by T6.

**T8 — Easing:** linear in this row. Ease in and ease out are a later choice on the frame.

**T9 — The scrubber shows it:** a tweened frame's cell in the bottom strip takes a distinct colour (owner), from the
design tokens (no new hex), with its hover label "Tween to the next frame".

## Slices

| Slice | What | Who |
|---|---|---|
| 5.40a | Core maths, pure functions on `StrokeRecord`s: arc-length resampling (T5), similarity fit and interpolation (T3), grouping (T4), HSB slide (T6), and `Tween.between(a: List<StrokeRecord>, b: List<StrokeRecord>, t): List<StrokeRecord>` giving the in-between lines in A's order with unmatched lines per T2 | ✅ Lead, 2026-10-08 (taken while Codex was idle): `core/anim/Tween.kt`, the 8 tests in `TweenTest`; a straight-line slide in place of the turn reddens tests 1, 3 and 5 |
| 5.40b | Doc: `Frame.tween: Boolean` (next DOC_VERSION at landing), validation (a tween needs `holdFrames > 1`), undo | after JB-5.20a |
| 5.40c | Screen and export: the toggle and the strip colour; playback and export draw the in-betweens through the 5.20 renderer | Lead, after JB-5.20e |

## 5.40a tests (hand-worked, each able to fail alone)

1. A straight line rotated 90° about its midpoint: at t = 0.5 every point is at the same distance from the pivot as at
   t = 0 (no shrink), and the line is at 45°.
2. A line translated only: points move in straight lines at constant speed; t = 0 and t = 1 give A and B exactly.
3. A line scaled ×4 about a point: at t = 0.5 it is ×2 (log-scale), not ×2.5.
4. A nudge (one end bent): the least-squares fit is a small turn, so the unmoved points stay within a quarter pixel
   of where they were at t = 0.5, and the bent point is within a quarter pixel of halfway.
5. Two lines rotated together as a selection form one group and turn round the selection's pivot; the same two lines
   rotated separately about their own centres form two groups.
6. Different sample counts (B is A sliced to 60%): resampled by arc length; ends map to ends.
7. HSB: red to blue goes through magenta, not green; grey to red keeps red's hue throughout.
8. Determinism: the same inputs give identical records (field for field), whatever order the lines arrive in.
