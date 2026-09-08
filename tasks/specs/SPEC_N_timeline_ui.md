# SPEC N — Timeline UI: wasted space, buried fade knobs, and lane legibility

**Difficulty: LOW-MEDIUM. All inside the timeline view and its row renderer.**
**Read `_RULES_READ_FIRST.md` first. Independent of SPEC L and SPEC M — different files.**

Five items from JoyRaptor's 2026-09-08 session, plus one design question he has already answered.

---

## 1. The ruler disappears

> "The ruler that sits below the timeline mini map but above the lanes — a slightly darker grey
> that shows zero seconds, two seconds, four seconds — that went away for a while, and it showed
> the lanes underneath."

Intermittent, so treat it as a state bug, not a drawing bug. The ruler is
`RULER_HEIGHT_DP = 22f` with `COLOR_RULER_BG/TEXT/TICK` in
`timeline/EditorTimelineView.java` (constants ~line 68–116, paint ~162).

Find every path that can skip the ruler band or collapse its height to zero — a layout race, a
zero/degenerate width, a scroll or zoom transient, a collapsed-row recalculation. **If the band's
height can ever compute to 0, that is your bug.** Reproduce it before fixing it; if you cannot
reproduce it, say so and add a guard that makes the height floor at its constant rather than
guessing at a cause.

---

## 2. Dead grey space above the spine (Note 20)

> "Above the spine there is a grey empty space that is so tall it goes above the fade handles on
> the main spine lane, which means almost an entire lane's worth is just being eaten up in empty
> space."

Reported on the Note 20 (SM-N986U, 1440×3088) — check whether it reproduces on the Note 9
(1440×2960) or is resolution/inset dependent. Screen space above the spine is the most expensive
real estate in the app: it pushes every layer lane down.

Find what reserves that band (a header, a padding constant, a minimum height, a safe-area inset
applied twice) and reclaim it. Report the measured before/after height in dp.

---

## 3. The spine needs a collapse caret

> "For when somebody is just mostly working with music files or a music video like me, where the
> audio was driving and every other thing was layers, and there was no spining, it doesn't make
> sense to have the spine taking up all that space. So we should give it a little tiny collapsible
> caret as well."

Layer and audio rows already collapse — `pendingCollapsedAudioTrack` (~line 409) and the row
headers' caret in `layers/LayerRowRenderer`. **Follow that existing pattern; do not invent a
second collapse mechanism.**

- Collapsed spine = a thin strip, same visual language as a collapsed audio row.
- The state persists with the project (sparse: absent = expanded, so existing projects re-save
  byte-identically).
- Collapsing must not change playback, export, or the spine's own selection.
- Trim handles, fade knobs and transitions are hidden while collapsed — a control you cannot
  usefully hit is worse than no control.

---

## 4. Fade knobs are painted over by layer rows — CONFIRMED IN CODE

> "I add an adjustment layer, and I notice that the adjustment layer is covering up the fade slider
> on the main spine. Those fade sliders should never be covered up by a layer. They should be on
> top of all the layers."

Confirmed in `EditorTimelineView.onDraw`: `drawMasterFadeKnobs` runs at ~line 2821, and the
multi-row layer UI (`LayerRowRenderer`, the "M6 hook") draws **after** it. So any layer row
overlapping that band paints straight over the knobs.

**Fix:** draw the master fade knobs (and their veils, if they can be overlapped too) after the
layer rows. Mind the two existing ordering constraints, both written in the comments there and
both from JoyRaptor — the veil must stay UNDER the green trim bars, and the knobs must stay ABOVE the
transition bands. Do not break either while fixing this.

---

## 5. Fade knobs should require an explicit tap

> "Those fade sliders should not even be visible unless I have clicked on that spine piece
> directly. If I haven't clicked on it and it's only selected because the playhead is there, they
> shouldn't show up."

Right now all three draw calls are gated on `selectedIndex >= 0` (~2798, 2802, 2820).
`selectedIndex` is set from several places, including playhead-driven selection (~1951) as well as
a real tap (~8544).

**Add a separate flag** — the segment was selected by a deliberate tap — and gate the fade veils
and knobs on that, not on `selectedIndex` alone. Trim handles keep their current behaviour unless
JoyRaptor says otherwise.

The flag must clear when: the selection changes, the playhead moves the selection on its own, the
spine collapses (item 3), or the user taps away. Say in your report exactly what clears it.

---

## 6. Alternating lane shading — JoyRaptor asked, the answer is yes

> "I'm also thinking the lanes might be helped if they alternate grey / darker grey. Do you think
> that's a good idea?"

**Yes, with two constraints.** Every DAW and spreadsheet does this because it genuinely helps the
eye track a row across a wide, scrolling surface — which is exactly this timeline's problem. But:

1. **Keep the contrast very low** — on the order of 3–5% lightness, not a visible stripe. The lane
   content is already strongly coloured (teal IMG lanes, green clips, amber carets); a bold stripe
   fights it and makes the timeline noisier, not clearer.
2. **Alternate by ROW POSITION, not by track identity.** Position-based banding stays still while
   the user scrolls; identity-based banding makes stripes jump around when rows are reordered or
   collapsed, which reads as flicker.

**JoyRaptor's clarification, 2026-09-08 — this is the acceptance test, not a nicety:**

> "It's just a visual way to track alternating tracks and should gracefully handle whenever a new
> track is added or collapsed. So it always looks like it's alternating, not having two bands
> together."

So the band index must be computed from the **VISIBLE row order at draw time**, and recomputed
after any add, remove, reorder, collapse or expand. Never cache it against a track id, an
underlying list index, or a position captured before a collapse — all three leave two same-coloured
rows adjacent the moment a row disappears from the middle. A collapsed row still occupies a visible
row, so it still consumes a band; a HIDDEN row does not. Test it: collapse a row in the middle of a
stack and confirm the rows below it re-band so the alternation is unbroken.

It must also not compete with the selection highlight — check a selected row on both band colours
before calling it done.

---

## Boundaries

- **Do not touch** `ui/faditor/transform/**`, `model/TextOverlayItem`, `TransformQuad`,
  `CornerPinTransformHost`, `PasteboardDimView`, or the reframe wiring. SPEC L and SPEC M own
  those, and both may be running while you work.
- Stage your work; do not commit; do not revert other lanes' staged changes.
- If the build breaks in a file you did not edit, stop and say so — do not fix it.

## Acceptance criteria

1. The ruler is always present, or the reason it vanishes is named and fixed.
2. The dead band above the spine is measurably smaller — give before/after dp.
3. The spine collapses and expands like an audio row, persists, and re-saves an untouched project
   byte-identically.
4. Fade knobs draw above every layer row, while the veil stays under the trim bars and the knobs
   stay above the transition bands.
5. Fade knobs appear only after a deliberate tap on that spine segment — never from a playhead
   move.
6. Lane banding is present, subtle, and stable while scrolling.
7. `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL, and these stay green:
   `run-pinbudget`, `run-speck`, `run-flip`, `run-mesh`, `run-rotation`, `run-preview-parity`,
   `run-frame-parity`, `run-persist-lint`.

## Deliver

Item by item: what you changed and the evidence. Screenshots from the **Note 9**
(`SANDBOX_SERIAL`) for items 2, 3, 4 and 6 — these are visual and a claim without a picture is
not verification. **Never install to or write on the Note 20 (`REAL_SERIAL`); it holds JoyRaptor's
real projects.** Build verdict; compile-verified vs device-verified.
