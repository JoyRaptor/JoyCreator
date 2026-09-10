# SPEC U — Grey family, focus-on-select, and adaptive vertical space

**Difficulty: MEDIUM. Three small items and one that needs real layout thought (item 4).**
**Read `_RULES_READ_FIRST.md` first.**

Four items from JoyRaptor, 2026-09-10. Item 4 is the substantial one — do the first three first, they
are quick wins, then give item 4 the time it needs.

---

## 1. The new elements are blue-shifted and clash

> "I am noticing the new elements are not grey but blue-shifted, starting to clash. The alternating
> lane colors are not the same grey family as the rest of the app. Same goes for new elements in the
> now-collapsible main spine."

The SPEC N banding used `0x661A1A24` / `0x662E2E3A` for row bodies and `0x99141420` / `0x99202030`
for headers. Those carry a blue cast — in `0x2E2E3A` the blue channel is 12 points above red and
green. Against the app's existing neutral greys that reads as a different palette.

**Find the app's established grey family first** and match it. Sample the actual constants already
in `EditorTimelineView` and `LayerRowRenderer` (`COLOR_RULER_BG = 0xFF141414` is a good anchor — a
pure neutral) and derive the two band tints from those, keeping SPEC N's contrast target of roughly
3–5% lightness. Do the same for anything the collapsed-spine work introduced.

The rule to apply: **new chrome takes its hue from the app, not from itself.** If a colour is
neutral grey elsewhere, it stays neutral grey here.

## 2. Tapping an object in the preview must bring its lane into view

> "When I tap on an object in preview, make sure if there's a lot of lanes in the timeline the item
> becomes in focus."

Selecting from the canvas already highlights the lane, but with many lanes that lane can be
scrolled out of sight, so the selection is invisible and the user cannot tell it worked.

Scroll the layer band so the selected item's row is visible. Minimum movement — if the row is
already on screen, do nothing at all; do not re-centre a row that was fine where it was. Animate
briefly rather than jumping.

## 3. Anything newly added becomes selected, and its lane comes into view

> "Same goes for if I add a new layer like a new adjustment layer or text layer or anything added
> new should become the thing selected and its lane in-focus."

Every add path — adjustment layer, text, image, sprite, visualizer, audio, whatever the Add menu
offers — should end with the new object **selected** and its lane **revealed**, using the same
reveal from item 2. Enumerate the add paths you found and confirm each one, in your report.

---

## 4. Adaptive vertical space — the substantial one

> "When I have an audio lane collapsed, there's currently a lot of negative space below the spine.
> And when I have the spine collapsed and the audio collapsed, there's still a lot of negative
> space. So let's have it where those things come down to make room for more lanes. Vertical space
> is always at a premium when there's a vertical video, so it's good for us to be economical with
> how we display our lanes. So if there's a bunch of audio tracks under the spine for an audio
> project, it prefers that. And if there's a bunch of lanes above because it's visually demanding
> and no audio lanes or collapsed audio lanes, that it goes to that. So whether you have a
> video-heavy or audio-heavy project, it's always utilizing the space the best."

The timeline is three stacked regions — the layer band above the spine, the spine, and the audio
band below. Today each appears to reserve its own share whether or not it needs it, so collapsing
one leaves a hole instead of giving the space to a neighbour.

**The behaviour: any region that is collapsed or has little content gives its space to the regions
that need it.** Collapse the audio band on a layer-heavy project and the layer band grows into it.
Collapse the spine on an audio project and the audio band grows. Neither should ever leave dead
grey.

Design notes, and these are constraints rather than suggestions:

- **Content-proportional, not fixed shares.** Ask each region what it *wants* (rows × row height,
  clamped to a sane minimum and maximum), then distribute the leftover in proportion to unmet
  demand. A collapsed region wants only its strip.
- **The spine keeps a floor unless explicitly collapsed** — it is the primary track and it must not
  be squeezed to nothing by a stack of layers.
- **No region may be scrolled to nothing.** If a region has content, it keeps enough height to show
  at least one row and to be grabbable.
- **Do not fight the user's own scroll.** If the layer band is scrollable and mid-scroll, growing
  it must not teleport their position.
- SPEC N reclaimed 28dp above the spine by making the measure pass agree with the layout pass.
  **Read that fix before touching this** — the same class of mismatch is exactly what produces
  "negative space", and the same trap is waiting here.

This is the kind of change that is easy to get subtly wrong in a way that only shows on a real
project. **Test on a project with many layer lanes AND on one with several audio tracks**, and show
both.

---

## Acceptance criteria

1. Band and collapsed-spine colours are neutral greys drawn from the app's existing family — state
   which existing constant you anchored to.
2. Tapping an object in the preview reveals its lane when off screen and does nothing when it is
   already visible.
3. Every add path leaves the new object selected with its lane revealed. List the paths.
4. Collapsing the audio band gives its space to the layer band; collapsing the spine gives its
   space to the audio band; neither leaves dead grey. **Device screenshots of both a layer-heavy
   and an audio-heavy project, before and after collapsing.**
5. No region can be squeezed below one usable row, and the spine keeps its floor unless collapsed.
6. `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL, and spect 11/11,
   specr 13/13, specq 7/7, mesh 66/66, spech 36/36, bakepop, escape, pinbudget, speck, flip,
   rotation, preview-parity, frame-parity, persist-lint all stay green.

## Boundaries

- You own `timeline/EditorTimelineView.java`, `layers/LayerRowRenderer.java`, and the
  selection/add wiring in `FaditorEditorActivity.java`.
- Do **not** touch `ui/faditor/transform/**`, `transform/mesh/**`, `TransformQuad`,
  `CornerPinTransformHost`, the bake path, or `overlay/TextOverlayLayer.java`. That is all
  verified transform work and a change there will be reverted.
- Note 9 is the test device. **Never write to the Note 20** — it holds JoyRaptor's real projects.

## Deliver

Per item: what you changed and the evidence. For item 4, the sizing rule you chose in plain words,
and screenshots of both project shapes. Build verdict; compile-verified vs device-verified.
