# C3/G7 paper-aware selection fade — exact dependency and tests

Read-only handoff, October25. Inspected coordinator source dfda430d3 in
C:/Temp/jb-select-reconcile-20261024. This document is published from the separate
ui/select-review-20261024 worktree. No engine/View/Activity/copy/overlay production edits,
builds, GPU/phone actions or preview changes. Coordinator accepted this dependency and
assigned the display hook/source-alpha replay/export exclusions to Engine Lead.

**C3 remains OPEN.** A generic Canvas mask, a preference toggle or forwarding dimRest alone
does not complete it. K25/K26 and the locked cyan/full-pill/borderless styling are preserved.

## Why an overlay-only implementation is insufficient

Locked D17 and the approved page specify this order: render the unselected drawing, fade
it toward the paper, then composite the actual selected sources at full source strength.
The page does exactly that in its sid block. The existing native pipeline already lifts
selected sources, but the Android SelectOverlayView is above the finished GL composition.
It has picker centreline/width geometry, not the exact selected textured/translucent source.

A rectangle/centreline hole would reveal unrelated artwork inside the hole, miss texture
gaps/soft edges and lag actual transformed/nudged preview shapes. Even the true source alpha
used as a hole does not produce the required composition at partial coverage.

For ordinary source-over, let B be the unselected background, P opaque paper, d fade
strength, and S the premultiplied selected colour with alpha a. In the renderer's working
channel space, the intended result is:

    S + (1-a) * ((1-d)*B + d*P)

The already-finished picture is C = S + (1-a)*B. A paper overlay with an alpha-shaped hole
instead yields:

    (1-d*(1-a))*C + d*(1-a)*P

Concrete counterexample, before byte rounding: B=(0,0,0), P=(1,1,1), S=(0.5,0,0), a=0.5,
d=0.66. Correct result=(0.83,0.33,0.33); alpha-hole overlay=(0.665,0.33,0.33). A binary
hole yields C=(0.5,0,0), leaving the background entirely unfaded there. Neither is correct.
Full strength means keeping selected source RGBA/operator intact; a translucent stroke's
final colour can legitimately change when its background fades.

## Existing source seam and minimal Lead-owned hook

At dfda430d3, GlPaintEngine.kt:

- Lift (:2733) owns exact selected tiles separately from the remaining scene. beginLift
  (:2749) stages rest versus selected; previewLift (:2810) refreshes selected source tiles;
  endLift (:2837) restores canonical display without a history step.
- drawLiftOverlay (:2854) composites the real current lifted sources through their homography.
- Main draw (:6018) reaches drawLiftOverlay at :6061; the composited path (:6083) reaches
  it at :6158 after the background compositor blit. Both screen paths need the same rule.
- Current paperArgb draw input already supplies the paper colour. Controller.refresh :236
  calls host.dimRest with picks.fadeRest for line selections and0 otherwise; preferences
  use0.66, matching the locked page (D17 prose rounds it to65%). Host.dimRest :416 is empty.

Minimal dependency for Engine Lead review:

1. An explicitly **screen-presentation-only** fade pass between finished unselected
   background and drawLiftOverlay in both screen paths. Fade to the current paper RGB,
   not black or cyan; source-over premultiplication must be correct. Use the existing real
   lift/preview rendering, not geometry repainted by the UI or a GL-to-bitmap readback.
2. A Lead-owned View/Host bridge for existing dimRest(alpha), bound to the live selection/
   lift and document/graphics generation. Reject nonfinite inputs safely, clamp strength,
   and request presentation redraw. Current committed sources/undo/dirty state stay untouched.
   The hook must know whether a valid corresponding lift is ready; a refused/missing lift
   must not dim the selected artwork or pretend it is preserved.
3. Clear/retire presentation on selection end, rejected/retired lift, context loss, Open,
   layer/cel/board transition and detach. Queued old-context fade requests cannot tint a new
   drawing. Commit/re-lift keeps the same presentation state only for the surviving selection;
   cancellation restores the real preview, never canonical data via a UI approximation.
4. Explicit draw-purpose exclusion for thumbnails, archive/saved looks, Working/recovery,
   copy/export, source tiles and readbacks that represent artwork. Do not assume an arbitrary
   targetFbo ID proves whether a call is presentation. Preserve whatever exact content each
   caller currently snapshots; the veil must never enter persisted or exported pixels.
5. Native selected alpha, texture, transforms, masks/clipping, physical address and operator
   handling remain the real renderer's responsibility. This handoff does not approve those
   existing paths merely because a veil can be inserted. Preserve K26 lines-over-realistic
   order and canonical per-source seq; UI overlays/controls remain above the presentation.

Pixel-loop outside tint/paint lift belongs to Phase B, not this line-only hook. No code for
Phase B is approved here. The overlay's existing nudge-before ghost and other marks remain
above the finished display, with their own original opacity/colour and no extra dim pass.

## Smallest meaningful acceptance matrix

These are planned tests, not new passing evidence. Lead owns source and shared validation
slots; no competing build is started by UI. Use a CPU composition oracle plus actual renderer
output, with existing shader precision/quantization conventions. An on/off screenshot alone
cannot prove source/history/export isolation.

| Case | Required check |
| --- | --- |
| White, black and kraft/grey paper | Unselected opaque colour fades toward the supplied paper at d=0.66; no fixed black wash. Selected opaque normal stroke stays unchanged. Check mixed artwork, not an empty canvas. |
| Partial alpha/coverage | Use the counterexample above plus alpha0/1 and soft edge samples; actual output matches background→veil→source, and intentionally broken whole-screen/alpha-hole controls fail. Preserve the selected source's alpha and colour, rather than brighten/repaint it. |
| Textured/variable-width sources | Native textured line and full-resolution vector watercolor, including transparent gaps and partially covered pixels; no centreline/box hole or stale original contour. This is appearance/composition proof, not an S-tier quality claim. |
| Live selection preview | Translate, scale/rotate, weight/colour preview and nudge/tip. Veil stays under the latest real preview at different zooms; cancel restores it, commit rebases it, and old/new tile regions do not leave a bright/dim trail. Existing20% nudge ghost remains20%, not multiplied by the fade. |
| Both draw paths and layer semantics | Exercise direct screen path and needsComposite path; multiple selected addresses, layer masks/clips/opacity and a line over realistic paint. If a source mode is unsupported, refuse honestly rather than flatten/simplify its operator. |
| Toggle/lifecycle/refusal | Off is identical to baseline. Selecting, toggling, changing paper, clearing and reopening do not add undo steps, dirty the document or alter records. Missing/failed lift, stale queued state, context loss and detach cannot leave the whole canvas faded. |
| Non-display isolation | With selection/dimming ON, compare canonical tile bytes, history/dirty state, copy/export, thumbnails, saved looks, archive/Working/recovery against OFF. They remain exactly equivalent under each existing caller's contract. Include a negative control that deliberately applies the screen veil offscreen. |
| Exact-source Note9 evidence | After Root's admitted validation and phone handoff, extend/run the hardened select_bench.sh on the identified combined APK. Preserve JSON, white/dark/kraft PNGs, source/APK identity, temperature and failures. No Note20, no monkey, no rotation setting changes. Synthetic bench proof does not replace physical pen-feel acceptance. |

Acceptance requires the actual hook plus semi-alpha, texture and live-preview evidence,
alongside persistence/export isolation. UI can review the resulting look and update C1/C2/C3
status after Lead supplies those results. Until then C3 is an explicit engine dependency;
no approximate UI implementation is being presented as finished work.
