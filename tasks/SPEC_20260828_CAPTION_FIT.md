# SPEC — Caption text fitting: auto-shrink, uniform sizing, and a Fit tab

**Written:** 2026-08-28 · **For:** an external agent (Muse) · **Owner:** JoyRaptor.

**Read `tasks/LANES.md` first and claim a lane.** Rule 6 (never run gradle) and the
WORKING-TREE HAZARD (`git add` each file as you write it) are not optional here.

---

## 1. What already exists — do not rebuild it

Landed 2026-08-28 in `bbd21a4b`:

- `CaptionStyle.maxWords` (default 6, range 1..60) — how many words a cue may hold.
- `CaptionPhrases.of(Transcript, int maxWords)` — the ONE grouping both renderers call.
  The no-arg `of(t)` still exists and defaults to 6.
- A jog dial on the caption Style row (`makeCaptionWordCountDial`): drag to change, tap
  to type.
- `CaptionStyle.autoFit` (boolean, default false) and `CaptionStyle.AUTO_FIT_MIN_SCALE`
  (0.45f). **Both are declared and deliberately unused.** They are yours to implement.

**The two renderers you must keep in agreement:**

| | Preview | Export |
|---|---|---|
| class | `transcript/CaptionOverlayView` | `export/CaptionExportRenderer` |
| draws | on a View canvas | into a bitmap for media3 |

`CaptionPhrases` exists precisely because these two once held independent copies of the
grouping rule (LEDGER §3g). **Any fitting logic you add must live in ONE shared place
that both call.** A private copy in each is the defect this spec exists to avoid.

---

## 2. The problem

JoyRaptor is putting ~136 scripture references on screen over a 7-minute song. With the
word cap raised he can now get a whole verse into one cue — and a long cue overflows
its box. He asked for the obvious remedy:

> "we could have a mode where it just tries to adjust the font size down to fit
> everything in the box. That way I could just make the caption box really big and then
> it would make it small. It could even be dynamic, so different paragraph densities
> would all fit into the same box."

---

## 3. What to build

### 3.1 A shared fitter

New pure-model class, e.g. `transcript/CaptionFit.java`. **No `android.*` imports** —
`CaptionPhrases` is pure so the JVM harness can exercise it, and this must be too.
Take a text-measuring callback rather than a `Paint`:

```java
public interface Measurer { float widthOf(String text, float textSizePx); }
```

It answers one question: **what text size makes this cue fit this box?**

- Wrap words to the box width at a candidate size, count lines, compare total height
  against the box height.
- Binary-search (or step down) the size until it fits.
- Never return below `authoredSize * CaptionStyle.AUTO_FIT_MIN_SCALE`. If the cue still
  does not fit at the floor, return the floor — clipping is the caller's problem to
  render sensibly, not a reason to shrink to nothing.

### 3.2 Three fit modes, not one

**This is the part JoyRaptor asked me to think about and it is the part most likely to be
got wrong.** Per-cue auto-fit makes the text breathe: every cue is a different length,
so the size changes on every phrase and the captions look unstable. Offer:

| Mode | Behaviour | When it is right |
|---|---|---|
| `OFF` | authored size, overflow as today | ordinary speech (**default**) |
| `UNIFORM` | measure EVERY cue, use the smallest size any of them needs, apply it to all | **JoyRaptor's case** — verses of differing length, stable on screen |
| `PER_CUE` | fit each cue independently | cues of similar length, or when maximum size matters more than stability |

Replace the boolean `autoFit` with this enum (keep reading `autoFit` from old JSON as
`UNIFORM` when true, so existing projects migrate).

`UNIFORM` must be computed ONCE per transcript+box+style, not per frame. Cache it and
invalidate when any of those change. Doing it per frame at 30fps in the export path
would be a repeat of the export bug fixed in `fb23f6dc`.

### 3.3 A Fit tab in the caption drawer

JoyRaptor: *"perhaps that's enough that it warrants its own tab in the drawer?"* — yes, at
three-plus controls it does. The caption drawer already has tabs; add one:

- Fit mode (Off / Uniform / Per cue)
- Words per caption — **MOVE the existing dial here**, and leave the Style row as it was
  before `bbd21a4b`. It was put inline because there was only one control; there are now
  several, and they belong together.
- Minimum size floor (percentage, default 45%)
- Max lines (optional; 0 = unlimited)

Keep the tab's height in line with the existing tabs. JoyRaptor has said more than once that
vertical space in that drawer is scarce.

---

## 4. Acceptance

1. A 30-word cue in a small box renders fully, shrunk, in preview AND export, at the
   same size in both. Compare a preview screenshot against an exported frame at the
   same timestamp.
2. `UNIFORM` gives every cue in the clip the same text size.
3. `OFF` renders byte-identically to today for a project that has never touched these
   controls. State how you verified this.
4. The floor is respected: a deliberately absurd cue stops shrinking at 45%.
5. Old projects load: `autoFit:true` in stored JSON becomes `UNIFORM`.

---

## 5. Traps

**5.1 — Two renderers, one rule.** Stated above; it is the whole reason `CaptionPhrases`
exists. If you find yourself writing the same measurement twice, stop.

**5.2 — Export is per-frame.** `CaptionExportRenderer` runs for every exported frame.
Anything you compute there must be cached across frames. The export path was 15 minutes
for a 46-second project until 2026-08-27 precisely because of per-frame work.

**5.3 — Do not run gradle** (LANES rule 6). Save, then read `build.log` for a
`BUILD SUCCESSFUL` whose mtime is newer than your last edit. The watcher stalls silently.

**5.4 — Encoding.** Do not use `perl -i` without `-CSD` on these files. A run without it
double-encoded 1446 characters in `FaditorEditorActivity.java` on 2026-08-28 and was
committed before anyone noticed, because it still compiled. Check `grep -c 'â' <file>`
returns 0 before you commit.

**5.5 — `getSelectedClip()` returns the WRONG clip silently** when nothing is selected
(falls back to `getClip(0)`, which on a music project is the auto-blank spine spacer).
Use `clipUnderPlayhead()`. Do not add a new caller.

**5.6 — Captions exist for AUDIO clips too.** `AudioClip` carries its own
`captionsEnabled`, `captionStyleId`, `captionCenterX/Y`, `captionSizeFraction`, and the
export builds one caption slot per audio clip. Whatever you add must work for both, or
say plainly in your report that it does not.

---

## 6. Out of scope

- Per-run rich text inside a cue (bold reference + plain body). Wanted, and a separate
  design — captions carry one style per cue today.
- The HTML slide object.
- Changing the default word cap away from 6.

---

## 7. Reporting

Real line counts from `git diff --numstat`. The literal last line of `build.log` with its
mtime. The §4 acceptance results in full, including the preview-vs-export comparison.
Say which checks need JoyRaptor's eye rather than claiming them.
