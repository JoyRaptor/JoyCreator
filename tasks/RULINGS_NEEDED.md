# Things only you can decide — 2026-08-21

Plain language. Each one says what the choice is, what I'd pick, and what it costs if we
get it wrong. Nothing here is blocked on code; it's blocked on your call.

---

## 1. Masks on images — how to make them visible while editing

**The situation.** An image with a mask exports correctly, but you can't see the mask while
editing unless that image ALSO has a blend or an effect on it.

**Why.** Images only get sent to the graphics chip (where masks are drawn) if they have a
blend, an effect, or a chroma key. A mask alone isn't on that list.

**The two ways to fix it:**
- **A. Draw the mask the ordinary way** — mask the image where it's already being drawn.
  More work for me. Keeps every image exactly where it is in the stacking order.
- **B. Add "has a mask" to the send-to-graphics-chip list.** One line. But images sent
  there get drawn at the graphics layer's depth, not their own lane's depth — which is the
  z-order bug you confirmed fixed two days ago. It would come back for masked images.

**I recommend A.** B is a one-line fix that re-breaks something you already paid to fix.

---

## 2. The 720p export change (already in the code, never ruled on)

Blur used to be measured in screen pixels, so the same blur setting produced a different
amount of blur in the editor than in the exported file, and a different amount again at
720p versus 1080p. It's now a fixed fraction of the frame height, so all three match.

**What this means for you:** a 720p export of a blurred project now looks like the editor
instead of blurrier. That is the fix — but it is a change to what comes out of the app, so
you should know it happened and say whether you want it.

**I recommend keeping it.** But if you have older exports you're matching against, say so.

---

## 3. The 4m16s of black added to your real project

When I built auto-extend, it appended 4 minutes 16 seconds of black to the end of "first
lecture on phone" to cover text and audio that ran past the video. Total length didn't
change and I verified the number, but it did edit your actual project.

**Keep it, or take it out?** It's one undo-sized change either way.

---

## 4. Should objects hanging past the end ALWAYS pull black in behind them?

Right now: yes, automatically, whenever an object ends past the last clip.

The alternative is to only do it when you ask. Automatic is friendlier but means the
project can get longer without you doing anything deliberate.

**I recommend keeping it automatic**, because the alternative is objects that silently
don't export.

---

## 5. "None" on a text animation now clears the timing too

Before, choosing None changed the label but left the entrance/exit timings in place, so the
box stayed stuck in animated behaviour with no way back. Now None wipes both.

The cost: if you set None and then pick an animation again, the timing starts fresh instead
of remembering what you had.

**I recommend keeping it.** "None" that doesn't mean none is how you lost an evening.

---

## 6. Where a black clip lands when you insert one

Right now it goes in AFTER the clip you have selected — a boundary you can predict.

The alternative is inserting at the playhead, which would mean splitting whatever clip
you're parked in the middle of. That's a destructive edit you didn't ask for, and Split
already exists for that.

**I recommend keeping it as-is**, but you're the one placing title cards.

---

## 7. Stills can now be stretched to an hour

5 seconds used to be both the starting length AND the maximum, which was never a decision
anyone made. Stills now arrive at 5 seconds and can be dragged to an hour.

**Is an hour the right ceiling?** It costs nothing at runtime; it's just a limit.

---

## 8. The big one: one authority for timeline-time to source-time

There are FOUR different pieces of code that answer "what point in what clip is this moment
on the timeline?", and an old audit found they already disagree. Every ripple, anchor and
hit-test rests on this.

**Consolidating them is the single most valuable structural fix left in the app** — and the
riskiest, because it touches machinery that has already cost you data loss twice.

**I recommend doing it, but only with a plan**: harness tests pinning current behaviour
first, then the merge, so any disagreement shows up as a failing test instead of as your
project.

**This is a "when", not a "whether" — but it needs a deliberate session, not a squeeze-in.**

---

## 9. The audio-editor direction

You've said you want Joy Creator to be a serious audio editor too, and named three
blockers: can't start a blank project, the main spine is video-only, and audio tracks
should show in the mini-map in their own colour.

The mini-map colour is small. The other two are architectural.

**I need to know if this is next, or after launch.** It changes what "solid" means.

---

## 10. Two bugs I can't chase without you

- **Text not rendering until you deleted an empty adjustment layer.** I need to know: was
  the empty layer BELOW the text, and did the text come back instantly or only after you
  scrubbed?
- **Whether audio actually extends the exported file.** The model says it should. Nobody
  has checked the output. If you have a project with audio past the last clip, exporting it
  and telling me the length settles it.

---

## 11. Look-and-feel sign-off

The Start / Span / End buttons are now your checkered-flag icons at 40dp with no backing
plate, grey until the marker is already where a tap would put it, then coloured to match
that marker. You haven't seen them yet. If they're too small or the grey is too dim, those
are one-line changes.
