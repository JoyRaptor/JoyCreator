# CARRY — make the dislodge one continuous motion (JoyRaptor, 2026-08-04)

## The complaint, verbatim

> "it still feels pretty clunky because i don't have any clear live preview as to what's happening
> i don't have a clear carryover state where the item is picking up in one place and seemingly
> chunking into another place and back again. Moving the thing off seems like a separate step where
> it moves off and then suddenly appears at another layer. Now I'm not exactly sure where it is,
> and then I can reposition it. it visually means to look seamless, picking up the clip, dragging
> it, and letting it go in another place."

## Diagnosis — two independent causes, both structural

**1. The model commits on PULL-UP, not on RELEASE.** `onMove`'s vertical-commit branch calls
`onClipDislodgeRequested` immediately, which runs `demoteToLayer` + a full timeline rebuild
mid-gesture. The clip really does leave the spine and become a layer object while the finger is
still down. Everything after that is a *second* interaction on an object that already moved — hence
"it moves off and then suddenly appears at another layer."

**2. Nothing ever floats under the finger.** `LayerRowRenderer` draws the drag proxy on a ROW
(`proxyRowTrackId`) at its resolved model X (renderer :722, :776-783). It is row-snapped by
construction, so even after adoption the clip sits at a row position rather than under the thumb.

No amount of animation fixes either one. The commit has to move to release, and the carried clip
has to be drawn free of the row grid.

## Design

**CARRY STATE** owned by `EditorTimelineView` (it owns spine rendering, so it can draw the card).

Entering: the vertical-commit branch no longer demotes. It captures
`carrySegIndex`, the grab offset inside the clip rect (`carryGrabDx/Dy`, from the ORIGINAL touch-
down point so the card does not jump), and the card size. **No model mutation.**

During:
- **Origin ghost** — the source clip stays drawn on the spine, dimmed/hollow. This is the answer to
  "I'm not sure where it is": you can always see where it came from.
- **Floating card** — the clip's thumbnail in a rounded rect with a shadow, at
  `(fingerX - grabDx, fingerY - grabDy)`. Reuse `drawReorderBlockThumbnail(canvas, rect, segIdx)`,
  which already center-crops a thumbnail into an arbitrary rounded rect.
- **Destination projection** — over the spine: the existing §3A.4 purple seam line. Over a layer
  row: highlight that row. Nowhere legal: no indicator, which reads as "this will go back".

Release — exactly ONE model op, so exactly one undo entry:
| dropped on | result |
|---|---|
| a layer row | demote to that layer AT THE DROPPED TIME |
| the spine, different seam | reorder to that seam |
| the spine, same place / nowhere legal | nothing — the card animates home |

## Why this also deletes complexity

`adoptDrag` exists only because the demote happened mid-gesture and the touch had to be handed to
a second engine. With the commit at release there is nothing to hand over for the spine→layer
direction. Keep `adoptDrag` for now (layer→layer drags still use the layer engine and are fine),
but the dislodge path stops needing it.

The undo merge (`mergeNextIntoTop`) also stops being needed for the dislodge, because one gesture
becomes one mutation instead of two. Leave the mechanism — it is correct and cheap — but the
dislodge no longer relies on it.

## Verification

`adb shell input draganddrop` drives all of this (it holds still before moving — see
HANDOFF_20260804c). Assert on `project.json`: exactly one clip moved, master count correct, and
**one** undo press restores the original order.
