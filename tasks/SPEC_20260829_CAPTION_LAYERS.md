# SPEC — Caption layers: more than one caption track, each on its own transcript

**Written:** 2026-08-29 · **For:** an external agent · **Owner:** JoyRaptor.

**Read `tasks/LANES.md` first and claim a lane named `SPEC_20260829_CAPTION_LAYERS`.**
Rule 6 (never run gradle — save and read `build.log`) and the WORKING-TREE HAZARD
(`git add` each file the moment you write it) are not optional.

**You are an implementer, not a reporter.** Claims of "built" are checked against
`build.log`'s LAST LINE and mtime. Claims of "verified on device" are checked against
`adb devices`. If the device is unplugged, say so — do not invent an observation.

---

## 1. Why this exists

JoyRaptor is building a 7-minute music video. The song is sung, and it cites ~136 scripture
references. He wants **two things on screen at once from the same audio**: the lyrics
(one transcript, one style) and the scripture references flashing up (a different
transcript, a different style, a different place on screen) so a viewer can pause and
read the citation.

His own workaround, in his words:

> "the only way I know to do that is to clone the audio, have it run in parallel on mute
> so it doesn't stack the audio, and then custom import the timing"

That workaround is real evidence of a missing feature, and it is a bad trade: a duplicate
decoder, a duplicate waveform bake, a muted clip that every future feature has to
special-case, and two things that can silently fall out of sync.

**He is much closer to this than he thinks, and that is the point of this spec.**

---

## 2. What ALREADY exists — read this before designing anything

A `Clip` **already holds a list of transcripts**, not one:

```java
// model/Clip.java:159
private final List<NamedTranscript> transcripts = new ArrayList<>();
// model/Clip.java:163
private int activeTranscriptIndex = -1;
```

So multiple transcripts per clip already import, persist, round-trip through
`ProjectStorage`, and are already selectable in the UI — the source picker landed
2026-08-28 in `b52e2727` (`SPEC_20260828_TRANSCRIPT_SOURCE`: header source name, a
`+ Source` chip, a long-press chooser).

**The ONLY thing stopping two from showing at once is that the binding is singular:**

| Field | Where | Problem |
|---|---|---|
| `activeTranscriptIndex` | `Clip.java:163` | one int — one transcript can render |
| `captionStyleId` | `Clip.java:247`, `AudioClip.java:150` | one style for the whole clip |
| caption position / size | on the VIEW (`CaptionOverlayView` `centerX`/`centerY`/`sizeFraction`) | **find where these persist** — they must become per-binding or both tracks stack in the same spot |

This is a **plumbing** change, not a new subsystem. Do not build a new object type. Do
not build a slide renderer. Do not touch the audio graph.

---

## 3. What to build

### 3.1 The model change

Replace the singular binding with a small list on `Clip` and `AudioClip`:

```java
/** One rendered caption track: which transcript, styled how, placed where. */
public static final class CaptionBinding {
    public String transcriptId;   // NamedTranscript.id — NOT an index (see §3.2)
    public String styleId;        // CaptionStyle id, e.g. "pop"
    public boolean enabled;
    public float centerX, centerY;   // 0..1 of canvas
    public float sizeFraction;
    public String label;          // shown in the UI, e.g. "Lyrics", "References"
}
private final List<CaptionBinding> captionBindings = new ArrayList<>();
```

**Cap it at 3.** More than three simultaneous caption tracks is unreadable on a phone
canvas and multiplies the per-frame text measurement cost. Enforce the cap in the model,
not only in the UI.

### 3.2 Migration — this must be exactly right or existing projects break

`activeTranscriptIndex` is an **index**; `CaptionBinding.transcriptId` is an **id**. That
change is deliberate: an index breaks when a transcript is deleted or reordered, and this
feature makes both far more likely. `NamedTranscript` already carries `id` (line 15).

On load, in `ProjectStorage`:

- If `captionBindings` is present in the JSON, use it.
- Otherwise synthesise ONE binding from the legacy fields:
  `transcriptId = transcripts.get(activeTranscriptIndex).id`,
  `styleId = captionStyleId`, `enabled = captionsEnabled`, position/size from wherever
  they persist today, `label = "Captions"`.
- If `activeTranscriptIndex` is −1 or out of range, produce **no** binding.

**Keep the legacy fields readable and keep writing them** (mirroring binding 0) for one
release, so a project saved by the new build still opens in the old one. Delete them in a
later pass, not this one.

Keep `getActiveNamedTranscript()` working — it should return binding 0's transcript. A
lot of code calls it (the transcript panel, the AI tools, word-tap seeking) and this spec
is not the place to rewrite those call sites.

### 3.3 The render change — preview

`transcript/CaptionOverlayView` currently renders one transcript with one style
(`setData(Transcript, CaptionStyle, Callback)` at line 123) and holds its own position
and size.

**Instantiate one `CaptionOverlayView` per enabled binding**, stacked in the preview
container in binding order. Do NOT add multi-track logic inside the view — the view stays
"draw one transcript in one style at one place", which is what it is good at. The
multiplicity lives in whoever owns the container.

Touch/drag: only **one** overlay is interactive at a time — the one whose binding is
selected in the drawer. The others must be `setClickable(false)` and pass touches
through, or the user will drag the wrong caption and not understand why. This matters
more than it sounds; get it wrong and the feature feels broken.

### 3.4 The render change — export

`export/CompositeExportOverlay` reads `getCaptionStyleId()` at lines 325 and 804;
`export/ExportManager:3093` skips when the style is `"hidden"`. Make each of these loop
over enabled bindings instead of reading the single field.

**Preview and export must draw from the SAME binding list.** `CaptionPhrases` and
`CaptionFit` exist precisely because the two renderers once held independent copies of a
rule (LEDGER §3g, and `SPEC_20260828_CAPTION_FIT` §1). Do not create a third divergence
here. If you need a helper to decide "which bindings are live at time T", write it once
in a shared pure class and call it from both.

**`CaptionFit.UNIFORM` is computed per transcript+box+style and cached.** With N
bindings, that cache must key on the binding, not the clip — otherwise track 2 inherits
track 1's fitted size. Check this; it is an easy miss and it will look like a font bug.

### 3.5 The UI

In the caption drawer, above the existing style controls, a compact **track list**:

```
 ● Lyrics          [pop]      👁
 ● References      [hidden]   👁      + Add caption track
```

- Tapping a row **selects** it — every existing control in the drawer (style, font, Fit
  tab, position, size, the words-per-cue dial) now edits the selected binding. This is the
  single most important UI rule in this spec: **the drawer does not grow, it retargets.**
- The eye toggles `enabled`.
- `+ Add caption track` opens the transcript chooser that already exists from `b52e2727`,
  then appends a binding with a sensible default position (track 2 goes ABOVE track 1, not
  on top of it — offset `centerY` by about 0.12 of canvas height).
- Long-press a row to rename or delete it.

On the timeline, `Timeline#getCaptionTracks()` (line 2093) builds a single read-only
`Track` row with the label `"CC"`. Make it build **one row per binding**, labelled with
the binding label. It is already a read-only view over clip-owned data, so this is a loop
change, not an ownership change — keep it that way. Captions must stay `Clip`-owned;
the Track is never a second source of truth.

---

## 4. Files

**Claim exactly these:**

```
app/src/main/java/com/fadcam/ui/faditor/model/Clip.java
app/src/main/java/com/fadcam/ui/faditor/model/AudioClip.java
app/src/main/java/com/fadcam/ui/faditor/model/Timeline.java          (getCaptionTracks only)
app/src/main/java/com/fadcam/ui/faditor/project/ProjectStorage.java
app/src/main/java/com/fadcam/ui/faditor/export/CompositeExportOverlay.java
app/src/main/java/com/fadcam/ui/faditor/export/ExportManager.java    (line ~3093 only)
app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java   (drawer + preview
   container; another lane may hold this file — coordinate on LANES before starting)
```

`transcript/CaptionOverlayView.java` should need **no change**. If you find yourself
editing it, you are probably putting multi-track logic in the wrong place — re-read §3.3.

**Out of scope:** the slide object (`SPEC_20260828_SLIDE_OBJECT`, parked), per-word rich
text inside a cue, and anything in the audio graph.

---

## 5. Acceptance

1. **Build.** `build.log` last line `BUILD SUCCESSFUL`, mtime newer than your last edit.
   Paste both.
2. **Migration, both directions.** Open a project saved by the OLD build: its captions
   render exactly as before, in the same place, same style. Save it in the NEW build,
   open it in the OLD build: captions still render. Screenshot all three states. **A
   project that loses its captions on load is a total failure of this spec** — check this
   before anything else.
3. **Two tracks, one clip.** On JoyRaptor's music project: lyrics transcript on track 1
   bottom-centre, scripture transcript on track 2 above it, different style. Screenshot
   with both visible and non-overlapping.
4. **Preview equals export.** Export ~15 s that contains both tracks. Compare a frame from
   the preview against the same frame from the export. Same text, same size, same
   position. Attach both frames. If they differ, §3.4 is not done.
5. **Independent fit.** Give the two tracks different `FitMode`s and confirm each fits
   independently — track 2 must not inherit track 1's size (§3.4).
6. **Selection retargets.** With track 2 selected, change the font. Track 1 must not
   change. Screenshot before/after.
7. **Drag isolation.** With track 1 selected, drag on top of track 2. Track 1 moves;
   track 2 does not.
8. **Device.** Paste `adb devices`. If unplugged, report §5.1 and §5.2-by-inspection only
   and say plainly that the visual checks are owed.

---

## 6. Traps carried from 2026-08-28

- `getSelectedClip()` silently returns `getClip(0)` when nothing is selected. On a music
  project that is the auto-blank black spacer, so caption edits land on the wrong clip.
  Use `clipUnderPlayhead()`.
- **A value written and never read** caused three separate bugs on 2026-08-28 — one of
  them (`27c8b75d`) was transcript words being written to a map nothing consumed. When
  your new binding does nothing, check that a renderer READS it before assuming your
  write is wrong.
- **Two answers to one question** caused three more. There is one list of bindings and
  one helper that decides what is live; preview and export both call it.
- Never `perl -i` without `-CSD`. Verify `grep -c 'â' <file>` is 0 on every file touched.
