# SPEC — Playback must survive a clip boundary

**Written:** 2026-08-25 · **For:** an external agent · **Evidence:** `_rescue_20260825_a32d24e2/seam_freeze_20260825.log`

Two defects, both at clip seams, both reproduced on JoyRaptor's Note 20 (SM-N986U) against his
main project. **B1 is the serious one** — it stops the editor being usable for reviewing an
edit. B2 is cosmetic but constant.

Read §4 before writing code.

---

## 1. B1 — the playhead freezes when playback crosses a seam

**Symptom, in JoyRaptor's words:** "the video achieved play, but the closed captioning completely
delinked and was paused as well as the timeline no longer moved while it played. Scrubbing
seemed to kind of reset it."

**Evidence.** Captured twice in one session, freezing at the identical value both times:

```
PHDIAG playing=true gapless=true playerPos=6087 sel=1 segAtHead=1 head=11261->11313 moved=true
PHDIAG playing=true gapless=true playerPos=6628 sel=1 segAtHead=2 head=11805->11854 moved=true
PHDIAG playing=true gapless=true playerPos=7173 sel=1 segAtHead=2 head=11811->11811 moved=false
PHDIAG playing=true gapless=true playerPos=9863 sel=1 segAtHead=2 head=11811->11811 moved=false
```

Read the columns: `playerPos` keeps climbing (video and audio keep playing), `segAtHead` has
moved to clip 2, `sel` is still clip 1, and `head` is pinned at 11811 forever. `drag=false`
throughout, so this is **not** the stranded-`userDragging` latch that was fixed on 2026-07-27 —
that self-heal is in the log too, firing separately and clearing cleanly.

**Mechanism (verified as far as stated; the agent must confirm the last step).**

- `updatePlayheadPosition()` (`FaditorEditorActivity:9823`) resolves the playhead against
  **`selectedClipIndex`**. With `sel` stuck at 1, it computes positions in clip 1's coordinate
  space and clamps at clip 1's out point — 11811.
- Nothing advances `selectedClipIndex` during playback at this boundary. `onGaplessSeam()`
  (`:4654`) is the path that would, via `advanceToSegment()`, and it **did not fire**: the log
  shows `onGaplessSeam -> segment 1 autoAdvance=true` for the previous seam and then nothing
  for segment 2.
- JoyRaptor's clips 1–5 are all slices of ONE source (`The_woman_and_the_5.mp4`) — the ordinary
  result of cutting one recording into many. During the freeze `playerPos` climbs past the
  length of any single clip, which says the player is running through one continuous window
  rather than transitioning between playlist items. No item transition, no seam callback.
- Dragging recovers because the **drag** path reconciles the two indices explicitly
  (`:2143`, `if (segmentIndex != selectedClipIndex) { selectedClipIndex = segmentIndex; … }`).
  The comment above it describes this same `sel` / `segAtHead` divergence, diagnosed on the
  Note 9 on 2026-07-28 and fixed **for dragging only**. The playback path was left.

**Required end state.**

1. Crossing a clip boundary during playback advances the app's notion of the current clip, so
   the playhead, timeline scroll, captions and every time-driven overlay keep moving.
2. It must hold when consecutive clips share one source file — that is the case that fails, and
   it is the common case for anyone who cuts a recording into pieces.
3. Playback must not run past a clip's out point. If the player is producing frames beyond the
   trim, the edit is not being honoured; say so plainly if you find that is happening.
4. Scrubbing must still recover, and must not regress the 2026-07-27 drag self-heal.

**Do NOT** fix this by clamping the playhead differently or by polling `getSegmentAtPlayhead()`
from the UI tick and forcing a selection change. Both hide the divergence rather than removing
it, and this bug has already been half-fixed once that way — the drag path was patched and the
playback path was not, which is exactly why it is still here. Find where the boundary crossing
should be observed and make that one place authoritative for both paths.

---

## 2. B2 — the picture squashes at every seam

**Symptom:** at each clip transition the video compresses to roughly a third of its height,
centred, apparently at a wrong aspect ratio, then pops back to correct size. Present on the
Note 20 across many seams; not observed on the Note 9's smaller test project.

**Status: NOT diagnosed.** The capture that caught B1 carried no lines from the preview
renderer at all, because the tag filter used names those classes do not log under. That is a
gap in the measurement, not evidence of absence.

**Known-adjacent:** the legacy `PlayerView`-transform path in `updatePreviewTransforms()` was
deleted on 2026-08-25 (commit `cbfb00d5`) partly on the theory that its stale
`effectiveVideoSize()` read caused this. JoyRaptor confirmed the squash **still happens** on a
build that includes that deletion, so that theory is dead. Do not re-derive it.

**Method — measure before theorising.** Capture with the real tags (find them by grepping the
compositor for its `TAG` constants), play across several seams, and establish which surface
changes size and when relative to the transition. Only then propose a cause.

---

## 3. What is already fixed — do not re-open

- **Image z-order** (`78032689`). Preview painted lanes in the exact reverse of the timeline's
  row order for any project with default track flags, which is nearly all of them. Confirmed
  fixed by JoyRaptor on device.
- **Preview crop** now follows the playhead clip and renders in the GL chain.
- **Blend modes between images work** — JoyRaptor: "images can affect other images if both images
  have a blending mode on." The apparent failure was the z inversion above. The remaining
  related gap is masks on NORMAL blend, specified in
  `tasks/SPEC_20260825_PREVIEW_MATCHES_EXPORT.md` §3A, which is a separate task.

---

## 4. Traps

**4.1 — Measure, then theorise.** Three theories died on this bug family in one day: that the
band clamp limited the PiP, that the dead transform path caused the squash, and that blend
modes could not composite image over image. Each was plausible, argued from the code, and
wrong. JoyRaptor's device is the authority; the log is the evidence.

**4.2 — `getSelectedClip()` returns the WRONG clip, silently.** It falls back to `getClip(0)`
when nothing is selected (`:822-832`), and `Timeline.getClip()` (`model/Timeline.java:289`) is
an unguarded `clips.get(index)` that throws on an empty timeline. This already caused the
preview to render the wrong clip's crop. ~120 call sites remain unaudited. Do not add one.

**4.3 — Build protocol.** `tasks/LANES.md` rule 6: **never run gradle/gradlew.** Save, then
read `build.log` for a fresh `BUILD SUCCESSFUL` whose mtime is NEWER than your last edit. The
watcher stalls silently — it prints "Waiting for changes to input files" while ignoring them,
and has needed a restart twice in two days. `bash tools/jvm-harness/typecheck.sh` is a
javac-only check you may run; it passed on a tree gradle could not compile, so it proves
nothing on its own.

**4.4 — Claim a lane** (LANES.md rule 1) and `git add` each file the moment you edit it
(WORKING-TREE HAZARD: uncommitted work here has been destroyed at least six times). Commit by
explicit path, never `git add -A`.

**4.5 — Never rewrite a whole file to make a small edit.** `584904f9` did and introduced a BOM
that stopped the tree compiling; repaired in `07f36175`. Check the first bytes are not
`EF BB BF` if you must rewrite.

---

## 5. Reporting

Real line counts from `git diff --numstat`. The literal last line of `typecheck.sh`. Say which
of B1's four required outcomes hold and which do not. B1 needs JoyRaptor to play across a seam on
his own project to confirm — say it needs his eye rather than claiming it. If B2 stays
undiagnosed, report what the corrected capture showed; a measurement that narrows the cause is
a real result.
