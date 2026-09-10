# SPEC V — Spine collapse: stale tape, invisible strip, and two mystery lines

**Difficulty: MEDIUM. All regressions from SPEC U item 4 / SPEC N item 3.**
**Read `_RULES_READ_FIRST.md` first, then `SPEC_U_timeline_polish_and_space.md` item 4.**

JoyRaptor found these on 2026-09-10, on the Note 20, within minutes of installing. Item 3 is the
one that matters most.

**Confirmed working and not to be touched:** the long-press ring's transparency. *"The transparency
level of the circle menu is appropriate."*

---

## 1. Two blue dotted lines across the timeline

> "There's two blue dotted lines that run across the sky, and I don't know what those are for. But
> they don't appear to be anything useful."

Visible in `scratchpad/n20_now.png` — two dashed blue horizontal rules spanning the full width,
above and below the spine's filmstrip row.

**Identify them before removing anything.** The likely candidate is the pending-lane drop slot:
`LayerRowRenderer.setPendingLaneGap` (~1301), driven from `EditorTimelineView` (~8164) by
`laneOpenPx`, which should only be non-zero while an item is being carried over the layer band
(`carryActive && carryOverLayerBand && carryDropState == CARRY_NEWLANE`). If it is drawing with no
drag in flight, something is leaving `laneOpenPx` or `pendingLaneGapIndex` set.

It may instead be a filmstrip border or a band divider that SPEC U's new geometry left stranded.
**Say which it actually was.** If it turns out to be deliberate and useful, explain what it is for
and make it read as intentional; if not, remove it.

### IDENTIFIED, 2026-09-10 — they are the SELECTION BOX, drawn stale

JoyRaptor, after a closer look, and this supersedes the guess above:

> "When I collapsed the main tape, it would still show the green selection box FULL SIZE along
> with two rails of dashed blue thin lines, which are different than the sprocket rails that are
> decorative. And it would leave it up even as I scrolled lanes. It would just stay still, but it
> wouldn't even be over the main collapsed spine."

So the dashed rules are the selection chrome's own edges, and there are **two separate faults** in
one symptom:

1. **The collapsed branch does not suppress the selection box.** `drawCollapsedSpine` runs instead
   of the segment loop, but the selection/trim chrome is still being drawn — at the EXPANDED
   height, because that is the geometry it was built from. Every draw call that is gated on
   `!spineCollapsed` needs auditing: something selection-related is outside those gates.
2. **It does not move with the band.** It stays pinned while the lanes scroll under it, so it is
   drawn in the wrong coordinate space — screen space where it should be content space, or from a
   rect captured before the collapse and never recomputed.

Fault 2 is the more interesting one: a stale rect that survives a scroll is a rect nobody is
recomputing. Find who owns it and make it derive from the current geometry rather than a snapshot.

**This replaces item 1's pending-lane-slot theory.** Check it anyway if the selection fix does not
account for both rules, but start here.

## 2. A collapsed spine is invisible, and its thumbnails are covered

> "When I collapse the main spine, I can't see where it is... when it's collapsed, I can't see the
> frames because the black bars go on top."

Two faults in `drawCollapsedSpine` (`EditorTimelineView` ~2921):

- **The collapsed strip does not read as the spine.** The user cannot tell where it went. It needs
  to be identifiable at a glance — that is the whole point of a collapsed row.
- **Black bars paint over the thumbnails.** JoyRaptor wants the tape visible when collapsed:
  *"the thumbnails tape [should] have higher [z] level."* So the frames must draw ABOVE whatever
  chrome is currently covering them.

Note this contradicts SPEC N §3's original wording ("no thumbnails are loaded while collapsed"),
which was written to save frame-extraction work on long projects. **JoyRaptor has now asked for the
tape to be visible while collapsed, so that ruling is superseded** — but keep the extraction cheap:
a collapsed strip needs far fewer, smaller frames than an expanded one, so extract at the collapsed
height rather than reusing expanded-size work.

## 3. THE BIG ONE — the tape stays squished after expanding

> "The biggest main problem is when collapsed, and then I expand, the tape remains small until I
> scroll past a clip border, and then the staleness stops and it refreshes."

**This is a cache keyed by a size that nothing invalidates when the row height changes.**

`EditorTimelineView.onDraw` calls `loadThumbnailsForSegment(i)` only inside the *expanded* branch,
per visible segment. Thumbnails extracted at one strip height are reused at another, so after an
expand the old, smaller frames keep being drawn — until the segment scrolls out of view and back
in, which forces a fresh extraction. That is exactly the "scroll past a clip border and it
refreshes" behaviour.

**The fix: collapsing or expanding the spine must invalidate the thumbnail cache (or its size key)
for the affected segments, and request re-extraction at the new height.** Do not paper over it with
a blanket invalidate on every draw — that would re-extract frames continuously and cost battery on
a long project, which is the opposite of what SPEC N §3 was protecting.

Look for the same class of bug anywhere else a row height can change under a cache: the audio band,
collapsed layer rows, the grab bar.

## 4. Collapsing the spine should take the audio drawer with it — and remember

> "To consider: when we collapse it, if the audio drawer is out, the audio drawer needs to retract
> and collapse, and remember it's out so that when we expand it, it also expands the audio drawer.
> But only if the audio drawer was actually expanded."

A paired collapse with memory:

- Collapsing the spine while the clip's audio drawer is **open** → close the drawer, and remember
  that it was open.
- Expanding the spine → reopen it, **only if** it was open at collapse time.
- Collapsing the spine while the drawer is **closed** → it stays closed on expand. No memory, no
  surprise.

The remembered flag is UI state, not project data — it must not be written to `project.json` and
must not create an undo step.

---

## Acceptance criteria

1. No unexplained dotted lines. State what they were.
2. A collapsed spine is obviously identifiable, and its thumbnail tape is visible above the chrome.
3. **Collapse then expand: the tape is full-size immediately, with no scrolling required.** This is
   the acceptance test JoyRaptor will run first.
4. Frame extraction while collapsed stays cheap — say what it costs versus expanded.
5. The audio drawer retracts with the spine and returns only if it was open. Nothing lands in
   `project.json`; no undo step.
6. `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL, and spect 11/11,
   specr 13/13, specq 7/7, mesh 66/66, spech 36/36, bakepop, escape, pinbudget, speck, flip,
   rotation, preview-parity, frame-parity, persist-lint all stay green.

## Boundaries

- You own `timeline/EditorTimelineView.java` and `layers/LayerRowRenderer.java`.
- Do **not** touch `ui/faditor/transform/**`, `transform/mesh/**`, `TransformQuad`,
  `CornerPinTransformHost`, the bake path, or `overlay/TextOverlayLayer.java`.
- Do **not** revert SPEC U item 4's space sharing — the collapse dividend is wanted. These are
  faults *in* it, not reasons to undo it.
- **The sandbox phone is the test device. NEVER install to or write on the Note 20** — it holds
  JoyRaptor's real projects. If only the Note 20 is attached, say so and stop rather than testing
  on it.

## Deliver

Item by item, with device screenshots for 1, 2 and 3 — item 3 needs a collapse/expand pair showing
the tape full-size immediately. What the dotted lines were. What collapsed extraction costs. Build
verdict; compile-verified vs device-verified, stated plainly per item.
