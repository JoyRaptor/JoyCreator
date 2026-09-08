# SPEC M — Bend is unusable, and the reframe/pasteboard UI needs work

**Difficulty: MEDIUM. UI and touch priority. Read `_RULES_READ_FIRST.md` first.**
**Depends on nothing. Can run alongside SPEC L (different files — see Boundaries).**

## 1. Bend covers its own escape hatch — CONFIRMED IN CODE

JoyRaptor, 2026-09-07:

> "Bend landed, doesn't work. Doesn't look like your prototype you made, covers the handles so
> once applied you cannot even access the corners to turn it off or scale or anything."

He is right, and it is visible in `TransformOverlayView`:

- **Draw order** (~line 464): the net is drawn *after* every structural handle —
  `if (bendMode && ...) drawBendNet(c, host);` — so the dots sit on top of the corner glyphs.
- **Hit order** (~line 1391): the net is hit-tested **first**, with an 18dp radius, before any
  structural handle. On a 3×3 net the four outer dots land exactly on the four quad corners.

So with Bend on, a tap on a corner grabs a **bend dot**, never the corner handle. The long-press
ring — the only way to turn Bend off — lives on a handle. **The user is locked in.** The comment
claiming "a slightly smaller radius so a near-miss still finds the structural handle underneath"
is true only for a near-miss; a direct corner tap always goes to the net.

### What to build

1. **Never put a net dot on a structural handle.** Either inset the outer ring of dots toward the
   picture's interior so they are visibly distinct from the corner glyphs, or drop the four corner
   dots entirely (the corners are already controllable by the corner handles).
2. **Structural handles win a tie.** If a tap is within range of both a bend dot and a handle, the
   handle takes it. Reverse today's priority for that overlap.
3. **An always-reachable way out.** Bend must be exitable without grabbing a corner — the same
   affordance that turned it on, a dedicated toggle, or a tap on empty canvas. State which you
   chose.
4. **It should look like the approved prototype.** `tasks/design/TRANSFORM_UI_FEEL.html` is the
   contract JoyRaptor signed off through six rounds. Read it before drawing anything.

### Acceptance

- With Bend on, a corner tap still scales; a dot tap still bends; neither is ambiguous.
- Bend can always be turned off, from any state, without a lucky tap.
- One dot-drag is still one undo press.

## 2. The pasteboard dim is darkening the whole preview

JoyRaptor: *"how come when I reframed now my entire preview looks like it has a dark film over it?"*
Confirmed on a device screenshot 2026-09-08: the video canvas renders flat grey instead of the
white slide it should show, and the surround is near-black.

`PasteboardDimView` fills itself with 75% black and punches an EVEN_ODD hole at the canvas rect.
The hole is clearly not landing on the canvas.

**Prime suspect — a coordinate-space mismatch. Verify before changing anything.** The view
hierarchy is:

```
player_container          <- PasteboardDimView is added HERE
  canvas_frame
    player_view           <- computeCanvasRect() returns playerView.getLeft()/getTop()
```

`computeCanvasRect()` measures `player_view` **relative to `canvas_frame`**, but the dim view draws
in **`player_container`** coordinates. If `canvas_frame` is inset within `player_container`, the
hole is offset by exactly that inset. Other consumers of `computeCanvasRect()` may rely on the
current meaning, so **do not change that method** — map the rect into the dim view's own space at
the call site, or make the dim view a child of the same parent the rect is measured against.

`PasteboardDimView.setCanvasRect` already logs through `TransformDiag`. **Get the real numbers
first**: the runbook's FASTEST PATH §4 shows how to pull `files/faditor/transform-diag.log` off
the phone. A `pasteboard view=WxH canvas=RectF(...)` line settles this in one read.

Also add the guard that should have caught it: if the hole does not intersect the view, or covers
less than a few percent of it, **draw nothing**. A dim that hides the whole picture is always
wrong, and should fail safe rather than fail dark.

### Acceptance

- The video canvas is at full brightness; only the surround is dimmed.
- A degenerate or off-view canvas rect draws no dim at all.
- Nothing dims during a layout transient (screen wake, drawer resize, surface churn).

## 3. The Reframe pill — JoyRaptor wants alternatives

> "The reframe UI button doesn't look good so I want you to explore alternatives."
> "Reframe pill needs a pointer pointing off screen (like a speech bubble)."

Current: a plain rounded "Reframe" pill parked at the nearest screen edge (screenshot 2026-09-08 —
a bare outlined lozenge, no indication of which way the object went).

**Note the ordering:** if SPEC L lands, objects should rarely leave the canvas at all, and this
becomes a rare-case affordance rather than a daily tool. Design it as a quiet safety net, not a
feature.

Offer JoyRaptor **three** options as a small visual mock (an HTML page under `tasks/design/`, the way
`TRANSFORM_UI_FEEL.html` and `ROTATION_DIAL_OPTIONS.html` were done — he picks by feel, not by
description):

- **A — directional bubble.** His own suggestion: a speech-bubble tail pointing off-screen toward
  the object, so the pill says *where* as well as *what*.
- **B — edge chevron.** No pill at all: a chevron/arrow on the edge nearest the object, with the
  object's distance implied by its size or opacity. Least chrome.
- **C — ghost outline.** Draw the object's quad, clamped to the screen edge at low opacity, as its
  own "come back" target. Tapping the ghost brings it home. No new widget at all.

Build all three as working mocks and record his pick in this file before implementing.

## 4. Off-canvas content should be visible, not invisible

JoyRaptor: *"currently anything off screen doesn't render on canvas. It should still show 25% opacity
off canvas so I can see exactly what is being clipped. We had something similar before we changed
to this hella biggy transformer."*

That is what the pasteboard dim was meant to do; §2 is why it is not working. Once the hole is
right, verify the *object* actually draws outside the canvas rect at reduced opacity rather than
being clipped away — a dim over nothing still shows nothing. Find whether the preview clips
overlay content to the canvas, and say what you found.

## Boundaries

- **Do not touch** `TransformQuad`, `CornerPinTransformHost`, or `normalizePin` — SPEC L owns the
  geometry. This spec is `TransformOverlayView`, `PasteboardDimView`, and the pill/dim wiring in
  `FaditorEditorActivity`.
- Do not change flip/fold behaviour.
- Tree is green at `c9cac612` plus staged work. If the build goes red in a file you did not edit,
  stop and say so.

## Deliver

Per section: what you changed, the evidence, and the device check. **The pasteboard numbers must
come from the phone's own log, not from reasoning.** Build verdict; compile-verified vs
device-verified.
