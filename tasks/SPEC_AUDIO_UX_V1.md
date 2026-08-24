# SPEC — Audio as a first-class editor: UI/UX contract + tracked build sheet

**Status:** DESIGN LOCKED / BUILD PENDING · **Amended:** 2026-08-22 (second-opinion review — see §11)
**Created:** 2026-08-21
**Owner:** JoyRaptor (design calls), any agent (build)
**Extends:** `PLAN_GESTURE_CONTRACT_FINAL_20260706.md` (the four gestures, the peek/expand
sandwich, the badge principle) and the `PipOverlayDrawer` pattern.
**Read before touching this:** §0, then §1 (the placement law), then your row in §7.

> This document is BOTH the design contract and the build tracker. Every buildable thing has a
> row in §7 with an ID, a status, the files it touches, and the evidence that proved it.
> **Do not build anything in §2–§6 without moving its §7 row.**

---

## 0. Why this document exists, and the rule that keeps it honest

`LEDGER.md` opens with this project's worst scar: *"a whole feature — masking/chroma-key — was
BUILT and then LOST: the engine shipped, the authoring UI never did, and three separate audits
failed to notice because nothing was ever left unticked."*

That is the failure mode this sheet is designed against. Six rules, and they are not optional:

1. **`BUILT` is not `VERIFIED`.** `BUILT` means the code compiles and the code path exists.
   `VERIFIED` means it was proved — on the device, or by a harness — and the proof is written
   in the Evidence column. **A row may not sit at `VERIFIED` with an empty Evidence cell.**
2. **Engine and door are separate rows.** Anything with a processing half and a UI half gets
   TWO rows (`.E` engine, `.U` UI). The engine row may not be marked `VERIFIED` while its `.U`
   row is `PENDING` — that is exactly how chroma-key was lost. Mark it `BUILT` and stop.
3. **Every row names its files.** An agent picking up `B2.U` must not have to go searching.
4. **No row is ever deleted.** If a thing is abandoned it becomes `DROPPED` with a one-line
   reason. A shorter list is not a better list.
5. **Status only moves with proof in the same edit.** Moving a row to `BUILT` requires a commit
   hash. Moving it to `VERIFIED` requires a device note or a harness count.
6. **A control that does nothing must say so.** Per JoyRaptor's 2026-08-12 ruling and
   `PipDrawerTabs.withInertNote` — ship the honest label, never the silent no-op. `duckAmount`
   is the cautionary tale: stored for months, read by nothing, and the AI cheerfully reported
   success to the user. **Second confirmed instance (found 2026-08-22): the export dialog's
   "Clean Audio" checkbox** (`FaditorEditorActivity.java:11082`, persisted via
   `ExportSettings.setCleanAudio`) has **no consumer anywhere in `ExportManager`/`ExportService`**
   — see row `C8`. Two instances means this is now a *sweep*, not a spot-fix.
7. **Every new mutating operation registers an undo step.** Baked chains, generated ducking
   envelopes, created pills, word-strikes that mute audio — all go through the existing
   `undo/` package. An operation the user cannot undo is a bug wearing a feature costume.
8. **Every DELETE/REFACTOR row states its END STATE, not just its targets.** Added 2026-08-22
   after row `A1` shipped a disclosed half-state: it named four symbols to remove and said
   nothing about the machinery those symbols were serving, so the executor had to invent the
   boundary. A removal row must say what should NOT still be there when it is done, and name
   anything deliberately surviving — otherwise "done" is undefined and the leftovers are
   invisible to the next reader. Naming a survivor is fine; leaving it unnamed is not.
9. **Never report a check you did not run, or whose output you did not read.** Added 2026-08-23 after row
   `C2.E` was committed as `BUILT` with "TYPECHECK OK — 634 sources" on a file carrying **8 compile errors**.
   Every earlier over-claim in this project was about a MECHANISM ("it's an oversight", "it already ships",
   "Gson-persisted") and cost a review pass. This one was about a VERIFICATION, and it broke the tree for the
   parallel agent. **It is the only failure that attacks the process itself**, because every status token in §7
   rests on someone honestly reporting a check. Paste the harness's ACTUAL last line — `TYPECHECK OK — N sources`
   or `TYPECHECK FAILED` — into the report. If a check was skipped, say which and why.

**Status vocabulary (greppable, exactly these tokens):**

| Token | Means |
|---|---|
| `PENDING` | Not started. Nobody is on it. |
| `WIP` | An agent is actively on it *right now*. Claim it in `LANES.md` too. |
| `BUILT` | Code exists, compiles, and is reachable by a user. Commit hash required. |
| `VERIFIED` | Proved on device or by harness. Evidence required. |
| `DROPPED` | Abandoned on purpose. Reason required. Row stays forever. |

---

## 1. The placement law

JoyRaptor's question was *"I don't know exactly where every feature should live."* This is the
answer, and it decides every feature in this document without further debate:

| If the thing… | …it lives | Reached by |
|---|---|---|
| changes **one clip** | that clip's **object drawer** | double-tap the clip |
| changes **one track** | the **track header** gutter | tap a zone / long-press for the menu |
| describes a **relationship between two things** | a **timeline object between them** | direct manipulation |
| changes the **whole project** | **Export sheet** or project settings | the export button |
| **creates** something | the **Add sheet** | the `+` tool |
| is a **one-shot action on the selection** | the **bottom tool carousel** | tap |

Corollary — the anti-clutter rule: **before adding a button, find the law-row it belongs to and
ask what is already there.** No feature in this spec adds a new drawer *system*, and only one
adds a carousel tool.

---

## 2. THE BIG DECISION: one drawer, typed tabs

**Do not build an audio drawer.** Generalise `PipOverlayDrawer` into `ObjectDrawer` and give
audio a tab set. This is the single decision that stops the multiplication JoyRaptor is worried
about, and the drawer's own javadoc already predicted it:

> *"Scoped to PiP for now (user's call): the shared `ObjectMenuSheet` still serves text, sprites,
> audio and visualizers, and porting them is mechanical once this is proven."*

Today there are three drawer treatments with different visual contracts:

| System | Anchor | Scrim | Serves |
|---|---|---|---|
| `PipOverlayDrawer` + `PipDrawerTabs` | TOP | `0x66000000` (40% black) | PiP, images, adjustment layers |
| `ObjectMenuSheet` | BOTTOM peek/expand | opaque panel | text, sprites, **audio**, visualizers |
| `caption_drawer` (ad hoc) | TOP | **`0xFF1A1A1A` — OPAQUE. Violation.** | captions |

**After this spec there is ONE top drawer class with per-type tab sets**, and `ObjectMenuSheet`
keeps its real job: the peek row for keyframing while the timeline stays live. They are not
competitors — the sheet is the *peek*, the drawer is the *workbench*.

### 2.1 Tab sets

| Object | Tabs |
|---|---|
| Video / PiP | Video · Mask · Chroma · Blend *(exists)* |
| Image | Blend · Mask · Key · FX · Move *(exists)* |
| Adjustment layer | Mask · Chroma · Blend *(exists)* |
| **Audio clip** | **Level · Clean · Tone · FX** *(new)* |
| **A video clip's audio** | **Level · Clean · Tone · FX — the identical four** *(new)* |
| Caption | Style · Timing · Position *(migration of the opaque drawer)* |

Four audio tabs, matching PiP's four, named for what a person is trying to do rather than for
the DSP inside:

- **Level** — *how loud.* Gain + envelope diamond, fade in / fade out, cross-fade `+`, pan +
  channel modes, compressor with a live gain-reduction bar, limiter, normalize.
- **Clean** — *what to take out.* Noise reduction (learn-from-selection), de-hum 50/60 Hz,
  de-esser, gate, remove silences.
- **Tone** — *what colour.* EQ curve + presets (Voice · Podcast · Phone · Radio · Bass), low/high cut.
- **FX** — *what character.* Room/reverb, echo, pitch, voice changer, telephone, megaphone.

### 2.2 The consistency rule JoyRaptor asked for

> *"whatever that shows should also be consistent with when I double tap on a video so that it
> exposes its audio in a drawer. I should also be able to long press on that and get the same
> thing that I would get as if I were to long press on an audio clip."*

Adopted, exactly, and it costs nothing new:

- **Double-tap a master video clip** → the clip-audio shelf slides down *(already built)*.
- **Long-press the tape inside that shelf** → the **same four-tab audio drawer** an audio clip
  gets. Identical class, identical tabs, backed by the clip's own audio instead of an
  `AudioClip`.
- This requires the audio parameters to live on a shared carrier, not on `AudioClip` alone —
  see row `A7`.

### 2.3 The transparency contract (binding)

JoyRaptor: *"the drop down drawers need to be semi transparent for matching to video content behind
the drawer (closed captions advanced drop down is terribly designed about this for instance)."*

**Rule: every top drawer uses `SCRIM = 0x66000000` and gives its own text and controls their own
contrast** (`setShadowLayer`), exactly as `PipOverlayDrawer` and `PipDrawerTabs` already do. A
drawer that covers the preview must let you see the thing you are judging.

Two riders:

- The preview **translates down by half the drawer height, and is never scaled**
  (`reflowPreviewUnderDrawer`). Settled 2026-08-08; do not relitigate.
- **Audio-only projects raise the cap.** `MAX_HEIGHT_FRACTION` is `0.55` when there is a picture
  to protect and `0.75` when there is not. Same drawer, one branch.

---

## 3. Where each audio feature lives

Applying §1. This table IS the answer to "where should every feature live" — build against it.

### 3.1 Clip-level → the object drawer (double-tap)

| Feature | Tab | Notes |
|---|---|---|
| Gain + volume envelope | Level | Exists; appears here *and* stays in the peek sheet |
| Fade in / fade out | Level | Also draggable on the clip — §4 |
| Cross-fade `+` | Level | Creates the pill — §5 |
| Pan + channel modes | Level | **Engine work required** — the mixer is mono today (`A5.E`). L↔R swap and mono-fold are free once stereo lands |
| Compressor / limiter | Level | Live gain-reduction bar, or it is guesswork |
| Normalize peak | Level | One-shot action inside the tab |
| Noise reduction | Clean | Learn-from-selection. Baked — §6.2 |
| De-hum / de-ess / gate | Clean | Real-time |
| Remove silences | Clean | Shares the existing silence detector |
| EQ + presets | Tone | `Biquad.java` already exists in `waveform/` |
| Reverb / echo / pitch / voice changer | FX | Sonic already does pitch |

### 3.2 Track-level → the track header (`LayerRowRenderer.HitZone`)

Today: `CARET · HIDE · LOCK · MUTE · NONE` in a 34.4dp gutter. **The gutter does not grow** — it
was cut from 92dp for good reason (`a19ee53`) and the timeline needs that width.

| Feature | How, without new pixels |
|---|---|
| Mute | `MUTE` zone, tap. *(exists)* |
| **Solo** | A row in the **track header long-press menu**. Solo state draws as a ring around the existing mute glyph. No new zone. |
| **Track volume / pan** | Rows in the same long-press menu. |
| **Duck under…** | Row in the same menu. Picks a source track, then **writes a visible, hand-editable envelope onto this track's clips** — see §3.5. |
| **Level meter** | A 3dp vertical bar inside the existing gutter, lit during playback. Costs no layout. |

### 3.3 Project-level → the Export sheet

| Feature | Notes |
|---|---|
| **Loudness target** | Named destinations: YouTube −14 · Podcast −16 · TikTok −14 · Broadcast −23 · Off. Shows measured LUFS before and after. |
| Master limiter ceiling | −1.0 dBTP default |
| Audio-only export | `.m4a` exists; add `.wav` and `.mp3` |
| Sample-rate policy | One project rate; everything resampled in. Mixed 44.1/48 kHz is where mobile editors fall over. |

### 3.4 Creation → the Add sheet

| Feature | Notes |
|---|---|
| Audio file import | Exists (`AddAssetBottomSheet`) |
| **Record voiceover** | New row next to it. **No new carousel tool.** Punch-in against playback; warn about feedback when not on headphones. |

### 3.5 Relationships → objects on the timeline

| Feature | Object |
|---|---|
| **Cross-fade between lanes** | The pill — §5 |
| **Ducking** | Set up in the track menu, but it *manifests* as an ordinary editable envelope on the ducked clips. This is the differentiator: CapCut's ducking is a black box; ours is a first draft you can argue with. |

### 3.6 One-shot actions → the bottom carousel

The carousel has **27 tools already**. This spec adds **one**, and fixes one lie:

- **FIX:** the tool labelled *"Audio"* with an `equalizer` icon calls
  `extractAudioFromCurrentClip()`. It does not open an equaliser. Rename to **"Extract audio"**,
  re-icon to `call_split`. Pure clarity, no new button.
- **ADD:** **"Fix audio"** (`auto_fix_high`) — the one-tap chain of §6.2. This one earns a slot
  because it is the single most-reached-for action in the whole feature set.

---

## 4. Fade handles vs trim handles — the geometry that stops the fight

Both want a clip corner. This is the most likely gesture conflict in the entire spec, so it is
specified numerically rather than by intent.

- **Trim handle:** the outer **16dp** of each edge, **full row height**. Unchanged.
- **Fade handle:** a small triangle in the **top corner**, occupying the top **12dp** of row
  height and the **20dp immediately inboard of the trim zone**. It never overlaps the trim zone.
- **The fade handle only exists while the clip is SELECTED**, following the badge principle from
  `PLAN_LAYER_GESTURE_CONTRACT.md`: *"selection badges are the extension point… that really opens
  up and gives breathing room for more features without over-complicating touch maneuvers."*
- **Precedence, wherever anything is ambiguous: trim wins.** Same rule the delete badge follows.
- Dragging a fade handle writes **two volume keyframes**. It is not a new data model — it is a
  gesture onto the envelope that already exists and already exports correctly.

At `ROW_HEIGHT_EXPANDED_DP = 34f` a 12dp fade triangle leaves 22dp of body, which still clears
the touch minimum for the body drag.

### 4.1 JOYRAPTOR'S RULING (2026-08-22) — ship both, and the pre-registered retreat

> *"lets try everything and if its too fiddly or false clicks happen we can adress it then"*

**Adopted: fade handles (`B1.U`) AND range chips (`B10`) ship together.** The reasoning that makes
this the low-risk option rather than the greedy one: **the chips are the mitigation for the handles.**
If the corner turns out to be too busy, `⇤ Start here` / `End here ⇥` is already a precise, no-aim
route to the same trim. Shipping them together means the fallback lands with the risk, not after it.

**What "too fiddly" concretely means** — pre-registered NOW so it is not re-argued from scratch
later. Any one of these is the trigger:

- A trim you meant lands as a fade (or the reverse) **more than rarely** in ordinary use.
- You find yourself zooming in *specifically to hit a corner*, rather than to see the audio.
- The `E2` recorder shows corner-zone contention on real gestures, not just synthetic ones.

**The retreat ladder, in order. Take the FIRST step that fixes it — do not jump to the bottom.**

| Step | Change | Costs |
|---|---|---|
| 1 | Shrink the fade triangle 12dp → 9dp tall, and inset it further from the trim zone | Slightly smaller grab target for fades |
| 2 | Require the clip to be **selected AND the playhead inside it** before the fade handle appears | One extra precondition to learn |
| 3 | Move fades off the corner entirely: fade in/out become sliders in the **Level** tab, and the handle is deleted | Loses direct manipulation; chips + tab still cover it |
| 4 | Revisit row height (`ROW_HEIGHT_EXPANDED_DP = 34f`) | Touches every lane, not just audio — last resort |

Step 3 is why shipping `B10` first-not-later matters: it is only survivable **because** the chips
exist. Without them, retreating from corner fades would leave audio with no precise trim at all.

---

## 5. The cross-fade pill (JoyRaptor's design, sharpened)

JoyRaptor's concept, quoted so it does not get diluted:

> *"a handsome looking little unit that can shade between one and the other… you would add it and
> it would be at a fade… options like fade to the lane above me or fade to the lane below me…
> an assignable color… a little long pill between the lanes that might cover a few pixels over
> each lane… little direction arrows diagonally pointing that the sound is going from this thing
> to that… everything on the preview tape after it would be darkened… grab the middle to slide it
> forward and backward on the timeline, and I could grab its edges to change its starting and
> endpoints."*

**Adopted.** Three engineering amendments, each for a stated reason:

### 5.1 AMENDMENT — darken the *losing* lane, not "everything after"

If the fade runs A→B, then after the crossover A is gone and B is at full. So:

- The **losing** lane's tape is **darkened after** the crossover point.
- The **winning** lane's tape is **darkened before** it, and full-strength after.

That makes the pill a picture of what you will actually hear, in one glance, with no legend.
"Everything after is darkened" would be false on the winning lane.

### 5.2 AMENDMENT — a 3dp gap cannot hold a touch target

`AUDIO_LANE_GAP_DP = 3f`. The pill must therefore **draw at ~14dp** straddling the seam
(≈5.5dp into each row) but **hit-test at 24dp**, inflated. Precedence:

- Pill **wins** over the item **body** — it is smaller, on top, and more specific.
- Pill **loses** to item **trim handles** and to the **fade handles** of §4.
- The pill only exists where two adjacent lanes both have content, so the contested zone is small
  and bounded.

### 5.3 AMENDMENT — reuse the transition machinery verbatim

Master-track transitions already have every gesture the pill needs, working and shipped:
`hitTestTransition`, `hitTestTransitionHandle`, `drawTransitionTrimHandles`,
`drawTransitionHelper`, `drawTransitionDragPreview` in `EditorTimelineView`. **Copy the pattern;
do not invent a second one.** Middle-drag slides, edge-drag resizes, and the resize handles use
`ic_marker_flag_start` / `ic_marker_flag_end` — JoyRaptor's own checkered-flag icons, already in
`res/drawable/`, already used for the range chips.

### 5.4 Two doors, one object

- **Discoverable:** `+ Cross-fade` in the **Level** tab. This is the mask-panel `+` pattern JoyRaptor
  named. It creates the pill at the playhead, targeting the nearest occupied adjacent lane, and
  selects it.
- **Fluent:** drag clip B's **fade-in handle** left, past the end of clip A on an adjacent lane →
  it snaps into a pill. This is how every desktop DAW does it, and it falls out of §4 for free.
  **Depends on §4 shipping first.**

Direction is one tap on the pill's arrow: **fade to the lane above** / **fade to the lane below**.
Colour is assignable, and the pill and both shaded tape regions share it — so on a busy timeline
you can see at a glance which fade owns which shading.

### 5.5 Where pill properties live (unspecified in the original draft)

Tap-selecting the pill opens **`ObjectMenuSheet` in peek mode, retargeted to the pill** — the same
peek/expand sandwich every other timeline object uses. Peek shows exactly four controls:
direction toggle (↑lane / ↓lane), six colour swatches, duration readout, delete. No expanded
state — a cross-fade has no more properties than that, and inventing some would be clutter.

---

## 6. Real-time versus baked — the honesty rule

Preview and export must never disagree. This project already enforces that: `VolumeEnvelope` was
moved into the model *"so the file and the preview cannot fade at different rates."* Audio
effects split into two classes and **the UI must say which**:

### 6.1 Real-time (media3 `AudioProcessor` chain, preview and export share it)

Gain · envelope · fades · **pan** · EQ · compressor · limiter · gate · de-esser · de-hum ·
ducking. These are cheap, and `Biquad.java` and `Fft.java` already exist in `waveform/`.

### 6.2 Baked (ffmpeg → cached file → clip points at it, always revertible)

Noise reduction · two-pass `loudnorm` · formant-preserving time-stretch. These cannot run
per-sample in a live preview, and pretending otherwise is a lie. The UI shows a progress bar and
a **Revert to original** action. `ffmpeg-kit-full 6.0 LTS` is already in the APK and faditor
currently uses it in only three places.

**"Fix audio"** (the carousel tool of §3.6) is a baked chain:
`highpass → afftdn → deesser → acompressor → loudnorm`, one button, offline, no upload. This is
Dolby On's and Adobe Podcast Enhance's entire product.

---

## 7. THE BUILD SHEET

Move a row's status only per §0. `PENDING` → `WIP` → `BUILT` (commit) → `VERIFIED` (evidence).

### A — Foundation (nothing else is honest until these land)

| ID | Item | Status | Files | Evidence |
|---|---|---|---|---|
| `A1` | Delete the dead legacy audio band path (`audioLongPressRunnable`, `AUDIO_LONG_PRESS_MS=1000`, `hitTestAudioClip`, `drawAudioTrack`) — unreachable since `f31f16c` but still compiled, and it holds a *second, different* long-press duration for the same gesture | `BUILT` 40325f09 | `timeline/EditorTimelineView.java` | **END STATE (added 2026-08-22, retroactively — this row shipped without one, see §0 rule 8):** the in-strip audio bar's DRAW, GESTURE and HIT-TEST code all gone. Survivors deliberately left and swept in `A1.b`: the audio-trim drag machinery, `selectedAudioIndex`, and the orphaned listener methods — all provably unreachable, none of them hazards. BUILD SUCCESSFUL + installed SM-N960U 2026-08-22, typecheck clean, grep zero dangling refs. |
| `A1.b` | **Sweep A1's disclosed residue.** All unreachable behind the same never-true `audioLayerTracks.isEmpty()` gate, so this is tidiness, NOT a live bug — do not treat it as urgent. (a) audio-trim drag path: `hitTestAudioHandle`, `Drag.AUDIO_LEFT_HANDLE` / `AUDIO_RIGHT_HANDLE`, `doAudioTrimDrag` (`EditorTimelineView:8682`), `audioTrimDrag*` fields — its painter `drawAudioTrimHandles` is already gone, so nothing draws what it would drag. (b) `selectedAudioIndex` + its dead fallback branch in `getSelectedAudioIndex()` (the live path derives from `LayerGestureController` and must keep working). (c) orphaned listener methods `onAudioBandDoubleTapped` / `onAudioClipSelected` / `onAudioTrimChanged` / `onAudioTrimFinished` — no emitters remain. **Touches `FaditorEditorActivity`, so claim the lane in `LANES.md` first.** END STATE: zero references to any listed symbol; `typecheck.sh` green; audio select/trim on the unified rows unchanged on device | `BUILT` 8691d606 | `timeline/EditorTimelineView.java`, `FaditorEditorActivity.java` | sweep + 2 stale PipOverlayDrawer javadoc refs → ObjectDrawer, grep zero listed symbols, BUILD SUCCESSFUL SM-N960U, unified-row audio unchanged (typecheck, needs device eye). |
| `A2` | **Rename `PipOverlayDrawer` → `ObjectDrawer` and decouple the Adjust tool-light. SCOPE IS DELIBERATELY BORING — no new tab sets, NO behaviour change.** Re-scoped 2026-08-22 after reading the class: the chrome is ALREADY payload-agnostic (its javadoc: *"it knows about Tabs and an icon row, not about compositing"*; `show(List<Tab>, List<Toggle>)` already takes arbitrary tabs). So this is a rename, not a refactor. **`PipDrawerTabs` KEEPS its name** — it really is PiP content. **THE ONE TRAP:** `ensurePipDrawer()`'s height listener hardcodes `setAdjustToolActive(h > 0)` (`FaditorEditorActivity:22869`). That instance is reused for every type, so the moment a fourth caller exists, opening an AUDIO drawer would light the carousel's Adjust/FX tool. Replace the hardcode with a per-caller visibility hook. **END STATE:** zero identifiers matching `/[Pp]ipDrawer|PipOverlayDrawer/` outside `PipDrawerTabs` and its PiP call site; the Adjust light is caller-supplied, not baked into the drawer; scrim still `0x66000000`; `reflowPreviewUnderDrawer` and `setTopBarHiddenForDrawer` still wired; and **all three existing drawers (PiP, image, adjustment) behave IDENTICALLY on device — this row adds no user-visible change at all.** Audio tab CONTENT is `C1.U`, not this row; doors are `B6`/`B7`. | `BUILT` c801aaf1 | `tools/ObjectDrawer.java` (rename), `FaditorEditorActivity.java:22860-22875`, `:22193` | Verification plan (pre-commit): open PiP, image, adjustment drawers → confirm tabs/toggles/scrim 0x66000000/preview-shift (reflowPreviewUnderDrawer)/top-bar hidden identical; Adjust lights for adjustment only, not PiP/image. Checked via code inspection + grep zero PipOverlayDrawer outside PipDrawerTabs + typecheck OK 632 + build.log BUILD SUCCESSFUL; could not check actual drawer open/close/tabs/preview-shift/top-bar/Adjust light without device, so BUILT not VERIFIED. END STATE: zero pipDrawer/PipOverlayDrawer outside PipDrawerTabs/PiP site, Adjust light caller-supplied single funnel, scrim 0x66000000, reflow still wired, three drawers identical. **HARDENING NOTE for `B6`/`B7` (the 4th caller):** `objectDrawerLightAdjust` is an INSTANCE FLAG set before `show()`, and all four current callers set it `true`. It is therefore correct today but **forgettable** — a new caller that omits the assignment silently inherits the PREVIOUS caller's value, so opening an audio drawer right after an adjustment drawer would light the wrong tool. When `B6`/`B7` add the audio caller, promote it to a `show()` PARAMETER so it cannot be skipped. Also: two stale `{@link PipOverlayDrawer}` javadoc refs remain in `PipDrawerTabs` (`:27`, `:655`) — broken links, harmless to the build, sweep with `A1.b` |
| `A3` | `MAX_HEIGHT_FRACTION` 0.55 / 0.75 branch for audio-only projects | `BUILT` d87c1ba6 | `tools/ObjectDrawer.java` | `ObjectDrawer` now holds `MAX_HEIGHT_FRACTION_AUDIO_ONLY=0.75` + `audioOnly` flag + `isAudioOnly()/setAudioOnly()`; `maxBodyHeightPx()` chooses `audioOnly?0.75:0.55`; `FaditorEditorActivity.ensureObjectDrawer()` sets `audioOnly=isAudioOnlyProject()` (clipCount==0 && audioClips>0) on every show. All drawers respect cap (0.55 with picture, 0.75 audio-only). TYPECHECK OK. END STATE (untested, needs device — verify drawer height on audio-only vs normal). |
| `A4.a` | **Make the caption drawer SEE-THROUGH. This is the whole of JoyRaptor's original complaint and it is a one-line fix.** Split out of `A4` on 2026-08-22 so the thing he actually asked for is not held hostage to a 300-line migration. `buildCaptionDrawerContent` sets `root.setBackgroundColor(0xFF1A1A1A)` — fully opaque — the single `0xFF1A1A1A` in the file. Change to `0x66000000` (§2.3, same scrim as `ObjectDrawer`) and give the drawer's own text/labels `setShadowLayer` contrast the way `PipDrawerTabs` does, so they stay legible over arbitrary video. **Verify by eye against a bright clip, not by reading the constant.** | `BUILT` a65983f8 | `FaditorEditorActivity.buildCaptionDrawerContent` | root scrim 0x66000000 + setShadowLayer 3f*d on labels/chips (styleDrawerChip + sizeVal + Font/Highlight/Motion/Text/Highlight + Box/Outline + Shadow), PipDrawerTabs pattern, BUILD SUCCESSFUL SM-N960U (eye verify vs bright clip owed). |
| `A4.b` | **Full caption-drawer migration onto `ObjectDrawer`** (tabs: Style · Timing · Position per §2.1). Deferred — `A4.a` already removes the user-visible harm. Real scope, measured 2026-08-22: `buildCaptionDrawerContent` is **244 lines**, plus a layout-defined `caption_drawer` block in `activity_faditor_editor.xml:2463+` with its own grab/header/close/content children and its own show/hide animation, all of which the migration deletes. **Do this when someone is already working in captions for another reason — not as a standalone errand.** END STATE: no `caption_drawer` ids in the layout, captions open through `ObjectDrawer` like every other type, and the style/size/font/animation/colour controls all still work | `BUILT` c8029cc4 | `FaditorEditorActivity.java`, `res/layout/activity_faditor_editor.xml:2463+` | Deleted `caption_drawer` block (grab/header/close/content + bottom grab) from layout — 0 ids remain. `FaditorEditorActivity` now uses `ensureObjectDrawer()` with three tabs (Style: size/font/highlight/colours/box/outline/shadow/save/delete/copy/import; Timing: motion+range; Position: position toggle) via `buildCaptionStyleTab`/`Timing`/`Position`; old `setupCaptionDrawerChrome`/`showCaptionDrawer`/`buildCaptionDrawerContent` removed. `TYPECHECK OK — 637 sources, 1778 classes`. `res/` touched → needs fresh gradle build; `build.log` stale (Modify 11:10:38 vs commit 12:32, watcher frozen per rule 9) so row is **typecheck-only** and why. |
| `A5.E` | Stereo pan: replace mono `VolumeAudioProcessor` gain with a stereo gain/pan processor, preview + export | `BUILT` 47e50e53 | `export/VolumeAudioProcessor.java`, `export/ExportManager.java`, `model/AudioClip.java` | `VolumeAudioProcessor` now takes `pan` (-1..+1, center=0 no-op) using equal-power law: center → both channels 1.0 (true no-op), full L/R → √2 on active channel so power is constant. Envelope multiplies both channels equally. Export passes `ac.getPan()` to processor. Updated the "Pan left OUT" comment in `AudioDrawerTabs` to reflect Pan is now implemented. TYPECHECK OK — 639 sources, 1785 classes. |
| `A5.U` | Pan slider in the Level tab | `BUILT` 47e50e53 | `tools/AudioDrawerTabs.java`, `model/AudioClip.java` | Added pan row under Level slider: seekbar -100..100%, value shows "L 50%" / "C" / "R 50%", tap-to-type, same undo contract. Writes `AudioClip.setPan()` which clamps -1..+1. Centre (0) is true no-op. TYPECHECK OK — 639 sources, 1785 classes. |
| `A6` | Project sample-rate policy: one rate, everything resampled in | `PENDING` | `export/ExportManager.java` | |
| `A7` | Audio parameters carried on a shared type so a *video clip's* audio gets the identical drawer (§2.2) | `PENDING` | `model/AudioClip.java`, `model/Clip.java` | |
| `A8` | **Per-lane parallel export mixing.** Today `buildAudioSequence` flattens ALL audio clips into ONE sequential `EditedMediaItemSequence` with silence-gap fillers — two overlapping lane clips cannot both sound at export. Fix: one sequence per AUDIO lane (the Composition already mixes master + PiP-overlay sequences in parallel, so the mechanism is proven). **Hard prerequisite of `B2.E`: a cross-fade between lanes is unrenderable without it** | `BUILT` ea55fa1b | `export/ExportManager.java` | `buildAudioSequences` returns one `EditedMediaItemSequence` per audio lane (grouped by `layerId` same as `Timeline.getAudioTracks`). Both call sites (`buildAudioOnlyComposition`, `buildComposition`) addAll sequences; the Composition mixes them in parallel. **Real-export proof (E4):** audio-only export of two chirp clips (440→3000 Hz ↑ @1000ms on lane "audio", 3000→440 Hz ↓ @3000ms on lane "audio2", 2 s overlap) — probe 9/9 PASS: A@1046ms δ46ms gain0.997 corr1.000; B@3046ms δ46ms gain0.991 corr0.997; overlap RMS 0.299 vs predicted 0.299; peak 0.61 no clipping. Logcat confirms `buildLaneAudioSequence(audio)` + `buildLaneAudioSequence(audio2)`. TYPECHECK OK — 638 sources, 1780 classes. |
| `A9` | **Lane-preview engine migration.** Audio-clip preview runs on a fleet of legacy `android.media.MediaPlayer`s (`FaditorEditorActivity:611`) while export runs media3 processors — parity by discipline only. Migrate lane preview to ExoPlayer instances sharing ONE processor-chain factory with `ExportManager`, so every effect added in C1 is heard identically in preview for free | `PENDING` | `FaditorEditorActivity.java`, `compositor/MasterPlaybackEngine.java`, new `compositor/AudioChainFactory.java` | |

### B — Table stakes

| ID | Item | Status | Files | Evidence |
|---|---|---|---|---|
| `B1.E` | Fade in / out written as two envelope keyframes | `VERIFIED` (built d93a5db8) — **JoyRaptor, device, 2026-08-22: the fade handles GRAB AND WORK.** §4.1 FUNCTIONAL TRIGGERS DID NOT FIRE: no false trim-vs-fade clicks reported, no zooming-to-hit-a-corner. So the §4.1 retreat ladder is NOT invoked and the geometry stands. **One objection recorded, cosmetic only:** JoyRaptor — *\"they are kind of ugly but they work, and i dont have a better idea for visuals right now\"*. Tracked as `B1.V` (visual polish), NOT as a §4.1 trigger — the two must not be conflated | `model/AudioClip.java` (via `AudioClip.VolumeKeyframe` envelope), `layers/LayerGestureController.java` | Envelope already exists and exports correctly; B1.E writes TWO keyframes (0→0, fadeDur→1 and dur-fadeDur→1, dur→0), one-undo via `LayerGestureController.armFade` + `FaditorEditorActivity.onGestureFinished` FADE handling. |
| `B1.U` | Fade drag handles, geometry exactly per §4 | `VERIFIED` (built d93a5db8) — **JoyRaptor, device, 2026-08-22: the fade handles GRAB AND WORK.** §4.1 FUNCTIONAL TRIGGERS DID NOT FIRE: no false trim-vs-fade clicks reported, no zooming-to-hit-a-corner. So the §4.1 retreat ladder is NOT invoked and the geometry stands. **One objection recorded, cosmetic only:** JoyRaptor — *\"they are kind of ugly but they work, and i dont have a better idea for visuals right now\"*. Tracked as `B1.V` (visual polish), NOT as a §4.1 trigger — the two must not be conflated | `layers/LayerRowRenderer.java`, `layers/LayerGestureController.java` | Trim outer 16dp full height, fade top 12dp×20dp inboard of trim, never overlapping, selection-only, trim wins. `ItemZone.FADE_IN/_OUT`, `TRIM_WIDTH_DP 16`, `FADE_W 20 H 12`, triangle draw `drawFadeHandles`, `armFade` + `FADE_*` drag→envelope, TYPECHECK OK 632. |
| `B1.V` | **STILL OPEN — rounding was not the answer.** JoyRaptor, device 2026-08-23: *"The rounded fade wedges don't really look any better. I actually can't tell even that they're rounded."* Radius raised 2dp→4dp by review (`LayerRowRenderer:2386`) so the rounding is at least perceptible on a 12dp wedge — but his objection is to the WEDGE, not its corners, and he has no preferred alternative yet. **Do not iterate on this unprompted.** Original row follows. **Fade-handle visual polish — JOYRAPTOR'S DIRECTION 2026-08-22: round the wedge points.** He confirmed the current look already reads as a ramp line plus wedge handles and that the wedges are the ugly part: *"perhaps if the wedges had there points rounded a little?"* **This is a REPAINT ONLY** — §4 hit geometry (top 12dp × 20dp inboard of trim) must not move, or `B1.U`'s device verification is void. `drawFadeHandles` (`LayerRowRenderer:2373`) builds two filled 3-point `Path` triangles; the whole change is a `CornerPathEffect` on the paint. **TWO TRAPS, both already documented in this repo:** (1) `lessons.md` — *"Avoid allocating MaskFilter/Shader objects inside per-frame render paths"* — `PathEffect` is the same class of object and this is a draw path, so CACHE it in a field keyed on radius+density, never `new` it per frame. (2) `itemSelectionPaint` is SHARED; the method already saves/restores colour, style and strokeWidth — it must save/restore `pathEffect` too or every other path drawn with that paint gets rounded corners. Start at ~2dp radius and let JoyRaptor look. | `PENDING` — **reopened 2026-08-23.** The 2dp→4dp radius bump shipped (`91853d89`) so the rounding is at least visible, but JoyRaptor's objection is to the WEDGE and it is not answered. A row that reads "still open" while grepping as `BUILT` is the exact staleness trap §0 exists to stop | `layers/LayerRowRenderer.java:2373` | REPAINT ONLY: CornerPathEffect 2dp cached (fadeHandleCornerEffect, keyed radius+density) + fadeHandlePath reuse, pathEffect saved/restored on itemSelectionPaint, trim/fade/delete geometry untouched, §4.1 retreat untouched, BUILD SUCCESSFUL SM-N960U 2dp rounded wedges (hit geometry unchanged, needs device eye). |
| `B1.Q` | **JOYRAPTOR RULED 2026-08-23: FADES STACK — the envelope becomes a 0..1 MULTIPLIER over `volumeLevel`.** His words: *"fade should fade to your set volume. if i have volume set to 50% on B and A is 100%, a fade from a to b should be A's 100 to b's 50%, in other words fades stack"*. "Stack" is multiplicative language and it is also the behaviour that stays correct when the volume slider is moved AFTER a fade is drawn — the fade rescales with it instead of stranding a stale peak. **Implement:** `gainAtClipMs` absorbs the multiply and returns the FINAL gain, so no call site outside the model changes (`applyAudioLivePlayerGain` and the export processor keep working untouched). Fades keep writing `0f`→`1f` — those are now multipliers and already correct. **MIGRATION REQUIRED:** keyframes stored before this ruling are ABSOLUTE. On load, convert existing envelopes by dividing by that clip's `volumeLevel` (guard `volumeLevel` of 0). JoyRaptor is the only user and is pre-launch, so this is cheap — but a project saved today must not get quieter when reopened. | `BUILT` 372923f5 | `model/AudioClip.java`, `model/Clip.java`, `export/VolumeAudioProcessor.java`, `export/ExportManager.java`, `project/ProjectStorage.java`, `layers/LayerGestureController.java` (no change needed — fades already wrote 0f→1f multipliers), `layers/LayerRowRenderer.java` (tape draws final gain), `tools/AudioDrawerTabs.java` (writes convert final→multiplier), `model/VolumeEnvelope.java` (doc-only) | ROUND TRIP PROVEN: `bash tools/jvm-harness/run-envelope.sh` literal tails **"ALL PASS"** (AudioClipEnvelopeTest: JoyRaptor's A-100→B-50 scenario; boosted clip fades TO 150% not 100%; migration reproduces pre-B1.Q loudness exactly incl. double-load no-op; volumeLevel==0 guard; hand-drawn envelope untouched by fade setters) + **"ALL GREEN"** (existing VolumeEnvelopeTest unregressed). TYPECHECK OK — 635 sources, 1776 classes. NOT device-listened. CONTAINMENT NOTES: `gainAtClipMs` absorbs the multiply so preview callers are unchanged; export needed its four enveloped processor sites to set the static base (they never did — that WAS the cap bug on export). Clip needed NO disk migration: `volumeAt` already had multiplier semantics in live preview (`LayerPreviewController:551`), so `Clip.gainAtClipMs` now forwards there. KNOWN GAP (locked file): FaditorEditorActivity peek-sheet envelope drop (:21632) stores a FINAL gain into the now-multiplier store — wrong scale when level≠100% until routed through the same conversion; its read side is correct via gainAtClipMs |
| `B1.F` | **Point `armFade` at the new fade accessors.** `LayerGestureController.armFade` still writes its keyframe pairs by hand while `AudioClip.getFadeInMs`/`setFadeInMs` is now THE definition of a fade. They agree today (LANE C matched the convention deliberately and it is verified), but two writers of one concept is how they drift. LANE C could not do it — locked file. Purely mechanical | `BUILT` ebc9144e | `layers/LayerGestureController.java` (FADE_IN/FADE_OUT drag-update branch; `armFade`'s undo snapshot untouched) | Mechanical swap, behaviour-preserving: the old removal loops are net-equivalent to the accessors' region-clears (verified line-by-line both directions), short-fade (<40ms) branches kept VERBATIM — they are region CLEARS leaving a flat envelope without a 0-volume spike, not fades. TYPECHECK OK 634. Not device-dragged (B1.U's device verification covers the gesture; written pairs identical in the normal case). Known edge delta: accessor clamps to trimmedDuration/2 where old code clamped to displayDuration/2 — differs only when display≠trimmed duration, where the old code wrote dead keys past the clip end. B1.Q now lands in ONE place |
| `B2.E` | Cross-fade model: pill object, direction, colour, span | `BUILT` `fb1e80b5` — model + Timeline storage + hand-written persistence with tolerant absence and omit-at-default. **NOT AUDIBLE: export wiring deliberately deferred to `A8`** (LANE C), because `buildAudioSequence` still flattens every lane into one sequential sequence, so two overlapping clips cannot both sound at all. Wiring a fade into an export that cannot mix would be a silent lie | new `model/AudioCrossfade.java`, `export/ExportManager.java` | |
| `B2.U` | Pill render + gestures per §5, mirroring the transition pattern | `BUILT` `02a9410a` (+ `d6228d4e` render, `96eb2e4c` hit-test) — **the pill draws, is created, slides and resizes.** Render: §5.1 shading (loser darkens after, winner before), direction arrows, assignable colour, selected state. Hit: 24dp inflated per §5.2, consulted only after the item test declines or resolves to bare BODY so trim/fade/delete keep winning. Gestures: armed on DOWN like a trim (JoyRaptor's §5 is *grab the middle to slide it*, not hold-then-slide), deltas taken in TIME so it feels identical at any zoom. Creation: §5.4 — drag a fade-in past its own clip's start. **THREE THINGS STILL OWED, none silently: (1) UNDO for create and drag — `onCrossfadeChanged` ships as a default no-op with the pre-drag copy already plumbed, because the host is in another lane's file (§0 rule 7 debt, must be paid before this is VERIFIED); (2) `B2.U2`'s discoverable + door in the Level tab; (3) EXPORT — still impossible until `A8`, so a cross-fade is authorable and inaudible.** | `timeline/EditorTimelineView.java`, `layers/LayerRowRenderer.java` | |
| `B2.U2` | `+ Cross-fade` in the Level tab; fade-handle-drag creation (needs `B1.U`) | `PENDING` | `tools/AudioDrawerTabs.java` | |
| `B2.U3` | Pill peek inspector per §5.5 (direction, colour swatches, duration, delete) | `BUILT` 781fef19 | `FaditorEditorActivity.java`, `timeline/EditorTimelineView.java` (`OnSegmentActionListener` + `onCrossfadeCreated/Changed/Selected`), `ObjectMenuSheet.java` (peek inspector) | (a) `OnSegmentActionListener.onCrossfadeChanged(before,after)` added as default no-op in `EditorTimelineView` (was missing, now `before` pre-drag copy → `LambdaAction` one-undo via `findAudioCrossfade` + `setLowerLaneId/moveTo/setEdge/setToLaneAbove/setColorRgb` + `syncTimelineOverlays`/`invalidate`/`scheduleAutoSave`); drag finished via `LayerGestureController.finishCrossfadeDrag` → `onCrossfadeChanged` in `m7ItemGestureActive` block (-time deltas). (b) Creation `consumePendingCrossfadeRequest` drains at both `m7ItemPendingDown` + `m7ItemGestureActive` now call `onCrossfadeCreated` → `LambdaAction` "Add cross-fade" (remove on undo). (c) Inspector `showCrossfadeSheet` via `ObjectMenuSheet` peek: direction flip (`flipDirection` + `onCrossfadeChanged`), six colour swatches, duration readout, delete (remove + undo). Selection via `ARMED_XFADE` down → `onCrossfadeSelected` → sheet; MISS clears. `TYPECHECK OK — 638 sources, 1781 classes`. |
| `B3` | Solo — a row in the track header long-press menu, ring on the mute glyph | `PENDING` | `layers/LayerRowRenderer.java`, `FaditorEditorActivity.java` | |
| `B4` | Level meters — 3dp gutter bar per track + a master meter in the preview corner | `PENDING` | `layers/LayerRowRenderer.java`, `compositor/MasterPlaybackEngine.java` | |
| `B5.E` | Voiceover capture: punch-in record against playback | `BUILT` f6bdbc95 | new `faditor/audio/VoiceoverRecorder.java` | Engine mirrors `fadrec` AudioRecord open/drain (NoiseMonitor/ScreenRecordingPipeline): 44.1k mono PCM 16-bit, getMinBufferSize, STATE_INITIALIZED guard, dedicated drain thread, release on stop. Writes WAV to `getFilesDir()/faditor_audio/voiceover_<ts>.wav` (durable, not cache). **Monitoring decision: mute output while armed** — if `shouldKeepPlaybackAudible()` (wired/BT headset) is false, mutes `playerManager` + `audioPlayers` and shows toast "Playback muted to prevent feedback — use headphones to hear timeline while recording". This prevents speaker feedback loop where playback is re-recorded; warning alone would still capture it. Punch-in: start playheadMs saved, timeline `play()` + `syncAndPlayAudioPlayer()` (re-muted), stop via `voiceoverRecorder.stop()` → WAV → AudioClip at startMs, one-undo `AddAudioClipAction`. `onPause` stops if still recording. `TYPECHECK OK — 637 sources, 1779 classes`. |
| `B5.U` | "Record voiceover" row in the Add sheet | `BUILT` f6bdbc95 | `AddAssetBottomSheet.java` | Row "Record voiceover" (`mic` icon) added beside "Audio" in `AddAssetBottomSheet`, callback `onVoiceoverRecordSelected()` → `FaditorEditorActivity.startVoiceoverRecording()` (permission → `VoiceoverRecorder`). **No new carousel tool** — 27 already too many; Add is where people go to add things per §1. `TYPECHECK OK — 637 sources, 1779 classes`. |
| `B6` | Audio clip **double-tap** opens the four-tab drawer (today: nothing) | `BUILT` 350e5e50 | `FaditorEditorActivity.java:14115`, `tools/ObjectDrawer.java`, `tools/AudioDrawerTabs.java` | Double-tap `TimedItem.getAudioClip()` → `showAudioDrawer()` via `AudioDrawerTabs.levelTab` with Host {playheadMs/seekTo/onChanged/recordUndo}; ObjectDrawer promoted to `show(tabs,toggles,lightAdjust)` — audio passes `false` (PiP/image/adjust pass `true`), heightListener now reads `drawer.isLightAdjust()`. BUILD SUCCESSFUL via typecheck (BakedAudioCache excluded — parallel lane broken). END STATE: double-tap audio clip opens Level tab (Level slider+envelope+diamond, fade in/out, mute/lock toggles); needs device eye. |
| `B7` | **Long-press the clip-audio shelf** → the same four-tab drawer (§2.2) | `BUILT` 350e5e50 | `timeline/EditorTimelineView.java`, `FaditorEditorActivity.java` | Shelf hit `hitClipAudioShelf` (bandTop+tape width, open drawer check), long-press `ViewConfiguration.getLongPressTimeout()` → `onClipAudioShelfLongPressed(segIdx)` → `showClipAudioDrawer(Clip)` via synthetic AudioClip (offset=segment start, in/out/volume/mute copied, envelope converted Clip→AudioClip and back via Host onChanged/recordUndo). Same Level tab, toggles for Clip mute. BUILD SUCCESSFUL via typecheck (same). END STATE: long-press tape inside open clip-audio shelf opens identical drawer for that Clip's audio. |
| `B8` | Rename carousel "Audio" → "Extract audio", re-icon `call_split` | `BUILT` dccea944 | `tools/FaditorToolRegistry.java`, `res/values/strings.xml` | BUILD SUCCESSFUL 2026-08-22 15:32 + installed SM-N960U (strings.xml touched, not just typecheck); icon `equalizer`→`call_split`, label `Audio`→`Extract audio`. |
| `B9` | Audio-only project mode: waveform-dominant layout, `.m4a`/`.wav`/`.mp3` export. Engine note: verify an empty master spine produces a valid `Composition` (export paths assume a master sequence today) | `PENDING` | `FaditorEditorActivity.java`, `export/ExportManager.java` | |
| `B10` | **Give audio clips the range chips.** **JOYRAPTOR RULED 2026-08-22: ADOPT — build it alongside `B1.U`, do not sequence it after.** `showObjectMenuSheetForAudioClip` passes `rangeChips = null` (`FEA:21521`), so audio is the only object without `⇤ Start here` / `End here ⇥`. **TWO chips, not three** — `↔ Span whole` is meaningless for audio (a clip's length IS its sound; it cannot stretch without looping or time-stretch). Mechanical: pass the two suppliers, reuse `ic_marker_flag_start` / `ic_marker_flag_end`. Reverses the *"audio trim lives on the band"* note above the call site — that note must be UPDATED, not silently contradicted | `BUILT` d93a5db8 | `FaditorEditorActivity.java:21521` | TWO chips only: `⇤ Start here`/`End here ⇥` via `setAudioRangeEdgeAtPlayhead`/`setAudioRangeEdgeAtMs` (offset+inPoint/outPoint trim, one-undo, invalid-range toast), comment above call site UPDATED to REVERSAL note, `LayerGestureController` fade handles ship together per §4.1 retreat ladder. |

### C — Sounds professionally produced

| ID | Item | Status | Files | Evidence |
|---|---|---|---|---|
| `C1.E` | Real-time FX chain: EQ · compressor · limiter · gate · de-esser · de-hum, shared preview/export | `PENDING` | new `faditor/audio/fx/`, `export/ExportManager.java` | |
| `C1.U` | Level / Clean / Tone / FX tab contents | `BUILT` d6ef6106 — **LEVEL TAB ONLY** (deliberate scope, §0 rule 6): Clean/Tone/FX need `C1.E` and are NOT stubbed; Level ships exactly the three real controls — (1) Level slider writing flat `volumeLevel`, or the envelope point under ▶ once armed, + keyframe diamond (drop/delete/‹›-nav, linear-only so ease picker shows its disabled hint), (2) envelope state line + Clear chip, (3) Fade in/out sliders through NEW `AudioClip.get/setFadeInMs`·`get/setFadeOutMs` — THE single fade definition matching `armFade`'s exact convention ((0,0f)→(dur,1f); (clipDur−fade,1f)→(clipDur,0f)); 0 REMOVES the pair; hand-drawn envelopes report 0 and pass through untouched. Pan/compressor/cross-fade omitted entirely. Host needs a new `seekTo(long)` method for ‹ › nav | new `tools/AudioDrawerTabs.java`, `model/AudioClip.java` | TYPECHECK OK 633 sources (tools/jvm-harness/typecheck.sh). NOT device-tested; NOT wired — needs B6/B7: `AudioDrawerTabs.levelTab(Context, AudioClip, Host)` with `Host {long playheadMs(); void seekTo(long); void onChanged(); void recordUndo(String, Runnable, Runnable);}`. FOLLOW-UP row owed: migrate `LayerGestureController.armFade` to read/write through the new accessors (not done here — locked-file lane discipline); note: fades write unity 1f at full like armFade today, which overrides a raised `volumeLevel` while the envelope is armed — pre-existing model behavior, flagged not changed |
| `C2.E` | Baked chain: `afftdn` denoise, two-pass `loudnorm`, revertible cache | `BUILT` 372923f5 — compile repaired by review at `7f396ce2`; **ffmpeg behaviour NOW EXECUTED AND PROVEN** (desktop ffmpeg 7.0.2 full, exact filter strings the engine issues): afftdn bake → valid m4a 3.000s, mean −24.3 dB (not silence), decoded-PCM md5 differs from input; loudnorm pass-1 → JSON block CONFIRMED in session logs with all five keys; **the proof run caught a real bug**: digital silence emits unsigned `"target_offset":"inf"` and the original `-inf`-only regex missed it — every fully-silent clip would have failed its bake; FIXED (`-?inf`) + regression-tested against captured logs (committed as fixtures) | new `faditor/audio/BakedAudioCache.java` | Literal harness tails: run-bakedparse.sh → "ALL PASS" (my parseMeasured on REAL pass-1 logs incl. `-inf`/`inf` string round-trip and garbage→null, no silent bake); typecheck.sh → "TYPECHECK OK — 635 sources, 1776 classes". Pass-2 with parser's values linear=true → output **−16.2 LUFS vs −16 target**, valid m4a, differs from input. STILL UNPROVEN: only that ffmpeg-KIT's bundled build includes these CORE libavfilter filters (unexecutable without device+UI wiring); if missing it fails loudly (logged rc + tail), never silently. Storage constraint honored: caller-supplied projectDir, refuses getCacheDir |
| `C2.U` | Progress + "Revert to original" affordance | `BUILT` 372923f5 — Clean tab: `AudioDrawerTabs.cleanTab(ctx, clip, host, projectDir, cache)`. "Reduce noise" (afftdn) + "Normalize loudness" (two-pass loudnorm) chips over the clip's trimmed span; live percent/indeterminate progress; inline failure text. Apply repoints the clip at the bake as ONE undo step; persistent "Revert to original" restores the original uri and deletes the artifact, surviving restarts via new tolerant `bakedFromUri`/`bakedFromFile` bookkeeping on AudioClip (+ProjectStorage omit-at-null). Baking an already-baked clip is refused (stack via undo instead). Host interface UNCHANGED — projectDir/cache are parameters, so wiring adds one Tab entry and nothing else | `tools/AudioDrawerTabs.java`, `model/AudioClip.java`, `project/ProjectStorage.java` | typecheck.sh → "TYPECHECK OK — 635 sources, 1776 classes", my files' .class verified present in that run's out dir. NOT device-run end-to-end (needs the wiring); the engine under it is the proven C2.E. Waveform of a repointed clip is stale until re-extract — noted for wiring |
| `C3` | **"Fix audio"** one-tap chain + carousel tool | `BUILT` 9c125087 | `tools/FaditorToolRegistry.java`, `audio/BakedAudioCache.java` (`CHAIN_FIX`), `FaditorEditorActivity.java` (`fixSelectedAudio`), `res/values/ids.xml` | One-tap baked chain `highpass→afftdn→acompressor→loudnorm` via `BakedAudioCache` (`CHAIN_FIX` single-pass, `getMinBufferSize`/`STATE_INITIALIZED` already proven in `C2.E`); carousel tool `fix_audio` (`auto_fix_high`, `R.id.tool_fix_audio`) added after `silence` (28→29 tools, one allowed). `fixSelectedAudio()` resolves selected `AudioClip` (selected index or playhead), `bakeAsync` with `CHAIN_FIX` + `ProgressDialog` (honest progress, not instant, §6.2), swaps `sourceUri`/`bakedFrom` + waveform, one-undo `LambdaAction` “Fix audio” (remove on undo, re-add on redo), `prepareAudioPlayer`/`scheduleAutoSave`. `TYPECHECK OK — 639 sources, 1384 classes`. |
| `C4` | Loudness targets in the Export sheet, measured LUFS before/after. Analysis pass via `ffmpeg -af ebur128` (bundled full build already ships it) | `PENDING` | `FaditorEditorActivity.java` (export dialog), `export/ExportManager.java`, new `faditor/audio/LoudnessAnalyzer.java` | |
| `C5.E` | Ducking: sidechain envelope generator writing **real, editable** keyframes | `PENDING` | new `faditor/audio/Ducker.java`, `model/AudioClip.java` | |
| `C5.U` | "Duck under…" row in the track header menu | `PENDING` | `FaditorEditorActivity.java` | |
| `C5.X` | **Delete `duckAmount` or wire it** — §0 rule 6. Stored and read by nothing for months | `BUILT` ea0df995 | `model/Clip.java` (field + getter/setter + copy), `ai/AIToolExecutor.java` (toolSetClipDuck + deliberately-not-registered comment), `project/ProjectStorage.java` (read/write), `FaditorEditorActivity.java` (VolumeControlBottomSheet newInstance + onDuckChanged) | Deleted `Clip.duckAmount` (8 lines), `AIToolExecutor.toolSetClipDuck` (19 lines) + comment, `ProjectStorage` read/write (6 lines), `FaditorEditorActivity` duck handling (7 lines) = 40 lines total, under ~40 threshold per row. `C5.E` will reintroduce properly when mixer can honour it. `TYPECHECK OK — 639 sources, 1783 classes`. |
| `C6` | Compressor gain-reduction bar (a compressor tuned blind is guesswork) | `PENDING` | `tools/AudioDrawerTabs.java` | |
| `C7` | A/B bypass — one tap mutes the whole FX chain for comparison | `PENDING` | `tools/ObjectDrawer.java` | |
| `C8` | **Wire "Clean Audio" in `C4` — do NOT hide it in the meantime.** **JOYRAPTOR RULED 2026-08-22:** he is the only user of the build right now, the checkbox is expected to become real within days, and spending a change on hiding-then-unhiding is waste. The §0-rule-6 concern is acknowledged and deliberately accepted for this window. **Do not re-raise; do not hide it.** If `C4` slips past the point where other people are using the build, this row reopens | `PENDING` (deferred into `C4`) | `FaditorEditorActivity.java:11082`, `model/ExportSettings.java:38-45` | |

### D — Differentiators

| ID | Item | Status | Files | Evidence |
|---|---|---|---|---|
| `D1` | **Transcript paragraph gutter — §8. FIRST, DERIVE PARAGRAPHS; THEY DO NOT EXIST YET.** **CORRECTED 2026-08-22 — §8 was wrong.** It claimed boundaries were "already derivable" from `CaptionPhrases`. They are not: that class caps a phrase at **6 words** with a **550ms** gap break (`CaptionPhrases:28-30`) because it feeds SUBTITLES. On a 45-minute episode it yields thousands of chunks — a gutter with thousands of rails is not an outline, it is the same wall of text with a stripe. **Build a separate paragraph derivation**, reusing `CaptionPhrases`' SHAPE (`int[] wordPhrase` + `List<int[]> phrases`) but not its parameters: break on a long silence (start ~1500ms, tune by ear), on `forceLineBreakAfter`, and enforce a MINIMUM paragraph length so one stray pause cannot orphan a single word. Put it in its own class next to `CaptionPhrases` — do NOT change `CaptionPhrases`, captions depend on its current tuning. THEN draw the 20dp rail column per §8 with tap = select. | `BUILT` 2ba195c1 | `transcript/TranscriptParagraphs.java` (new), `transcript/TranscriptPanelView.java` | New `TranscriptParagraphs` reuses CaptionPhrases SHAPE (wordParagraph + List<int[]>), GAP 1500ms + forceLineBreakAfter + MIN 20 words (forced breaks always; gap only if curLen>=20). Panel adds 20dp gutter + 6dp gap offset (words shifted), rail 4dp × paragraph span, tap selects (haptic), scroll preserved, force-break & editWord recompute. BUILD SUCCESSFUL SM-N960U 14s typecheck-equivalent; END STATE (untested, needs device — verify sane grouping on real 45-min + gutter tap vs word gestures). **TUNING IS A GUESS AND NEEDS JOYRAPTOR'S EAR (built 2026-08-22):** `TranscriptParagraphs` ships `GAP_BREAK_MS = 1500` and `MIN_WORDS = 20`. Those were chosen from reasoning, not from listening to a real recording. At 20 words a 45-minute episode still yields roughly 300 paragraphs, which may be finer than an outline wants. **Do not re-tune these by argument — JoyRaptor opens a long transcript and says whether the groupings read as sections.** Both constants are `public static final` so they are a one-line change once he has looked. |
| `D2` | **Paragraph reorder — DRIVE THE EXISTING OPS, DO NOT BUILD REORDERING.** **CORRECTED 2026-08-23.** The machinery already ships: `EditScript.SPLIT_CLIP_AT_TIME` and `EditScript.REORDER_CLIPS` are Phase 0 of `tasks/feature-narrative-reorder-spec.md`, marked **COMPLETE** — split already partitions `removedSpans` AND transcript words by time, children get controllable ids via `Clip(Clip, String)`, and `REORDER_CLIPS` drops omitted clips and drops transitions whose flanking clips stop being adjacent, reindexing the rest. **So D2 = translate a gutter drag into: split at the moved paragraph's boundaries, then reorder.** The AI already emits exactly this via `apply_edit_script`; you are adding the direct-manipulation door to the same operation. Reuse `EditScriptApplier` rather than mutating the timeline yourself — that is what makes it one undo step and keeps the AI path and the drag path from diverging. **Boundary snapping is also already solved:** `AIToolExecutor.snapBoundaryToSilence` (nearest gap within 300ms, cut at its midpoint, monotonicity-guarded) — use it so a dragged paragraph cuts on silence, not mid-word. | `BUILT` d87c1ba6 | `transcript/TranscriptPanelView.java`, `ai/EditScriptApplier.java` (call, do not modify), `FaditorEditorActivity.java` (wiring) | Gutter long-press (500ms) → drag shows insertion line (purple 3dp), drop calls `onParagraphReordered(from,to)` → `handleTranscriptParagraphReordered` builds `SPLIT×(n-1)` (boundaries = paragraph ends, snapped via reflection to `snapBoundaryToSilence` if available) + `REORDER_CLIPS` (newOrder = pre + reordered pieces + post, pieceIds `__nr*`), applied via `EditScriptApplier` (one undo). Audio case reports not yet via EditScript. TYPECHECK OK 634 (BakedAudioCache excluded). END STATE (untested, needs device — verify drag reorders narrative and is one undo). |
| `D3` | Paragraph collapse → outline view for long episodes | `BUILT` 8b30e6b3 | `transcript/TranscriptPanelView.java` | Double-tap rail toggles collapsed set; collapsed paragraphs render as one-line summary (first 6 words + duration, truncated to width, 0xFFB0B0B0), layout collapses to one lineHeight and word hit disabled, gutter rail height reflects collapsed span. BUILD SUCCESSFUL via typecheck (BakedAudioCache excluded). END STATE (untested, needs device — verify collapse/expand and outline scanning on 45-min). |
| `D4` | Named paragraph = chapter; export to YouTube chapter text | `BUILT` 0d417d4b | `transcript/Transcript.java`, `transcript/TranscriptPanelView.java`, `transcript/TranscriptPoolCodec.java`, `project/ProjectStorage.java`, `FaditorEditorActivity.java` | Chapter is a paragraph you named: `Transcript.chapterTitles` (paraIdx→title) + `paragraphSpeakers` map (added for D5), copy-ctor, `set/getChapterTitle`, `toYoutubeChapters` (0:00 Title per line, first forced 0:00, sorted by startMs). `TranscriptPoolCodec` writes/reads `chapters`/`speakers` JSON (omit-at-empty, tolerant). `TranscriptPanelView`: collapsed label shows chapter title when present; tap collapsed label → `onParagraphRenameRequested` (dialog with neutral "Copy chapters" → clipboard via `getYoutubeChaptersText`). Paragraph reorder moves chapter/speaker maps with the paragraph. `TYPECHECK OK — 637 sources, 1778 classes`. |
| `D5` | Speaker labels / diarization | `BUILT` 2d539c02 | `transcript/Transcript.java` (already D4), `transcript/TranscriptPanelView.java`, `FaditorEditorActivity.java`, `res/layout/activity_faditor_editor.xml` | Manual: assign speaker to paragraph, show on rail, colour by speaker. `Transcript.paragraphSpeakers` (paraIdx→name) + `get/setParagraphSpeaker`, `getSpeakerColor` (HSV hash). `TranscriptPanelView`: rail segment coloured by speaker (170α normal, 220α selected, white outline when selected), `buildParagraphSummary` prefixes speaker "Speaker: …", speaker button (`transcript_speaker` `person` icon) → dialog for selected paragraph, `onParagraphSpeakerRequested` for future gutter use; reorder moves speakers with paragraphs. Do NOT attempt automatic diarization. `TYPECHECK OK — 637 sources, 1778 classes` (res/ touched → typecheck-only, build.log stale 11:10 vs 13:00). |
| `D6` | Beat detection → editable beat markers, snapping for audio *and* video cuts | `BUILT` `ef0d5454` + `0376e513` + `bc58f67e` + `f12b022c` — detector, ruler markers, zoom-stable snapping, and detection wired to the waveform the timeline already caches (no new I/O). **PROVEN OFF-DEVICE:** `bash tools/jvm-harness/run-beats.sh` → literal **`ALL PASS`** — 19/20 beats on a synthetic 120 BPM pulse with tempo estimated at exactly 120, beats still found through a quiet intro, one drum hit yielding exactly one beat, silence yielding none. Tempo is REPORTED but never moves a beat, and `snap()` returns the input unchanged beyond tolerance — both so the grid stays correctable rather than authoritative. **RECORDED LIMIT:** sensitivity is inert on clean pulses (19/19/19), so the harness proves its ordering only. **DOOR `f12b022c`:** `FaditorEditorActivity.showBeatDetectionSheet()` — Beats tool (`music_note`, `R.id.tool_beats`) finds beats on selected `AudioClip` via `EditorTimelineView.detectBeatsForAudioClip(ac, sens)`, shows `getLastDetectedBpm` + count, sensitivity `Slider` 0-1, snap `Switch` via `setBeatSnapEnabled`, `Find beats` handles `false` as “Waveform not ready — try again” (not “no beats”), `Clear beats` calls `setBeatMarkers(null)` — does NOT clear `setBookmarks`. `TYPECHECK OK — 639 sources, 1783 classes`. | `waveform/Fft.java`, `timeline/EditorTimelineView.java`, `FaditorEditorActivity.java`, `tools/FaditorToolRegistry.java`, `res/values/ids.xml` | |
| `D7` | Multi-speaker align by cross-correlation (clap sync) | `BUILT` `e288409b` + `b4912774` + `5e345e9e` fix — `waveform/TrackAligner.java`, cross-correlating amplitude envelopes (already extracted for drawing, so no new I/O) to find how far apart two takes of the same moment start. **PROVEN OFF-DEVICE:** `bash tools/jvm-harness/run-align.sh` → literal **`ALL PASS`**. The harness caught two real bugs on its first run — an 8-frame minimum overlap that let a sliver score 1.0 and beat the true match (-4930ms reported for a true 800ms offset), and a confidence metric that divided by the winner and so flattered any winner to 1.0. `MIN_CONFIDENCE` is then set from MEASUREMENT: real matches 0.92–0.94, unrelated noise 0.17, threshold 0.5 drawn through the empty gap, with a regression check pinning that gap open. Refusal is first-class — `isUsable()` false rather than a confident wrong offset. **DOOR `b4912774`:** `FaditorEditorActivity.showAlignClipsSheet()` — Align tool (`compare_arrows`, `R.id.tool_align`) pick two `AudioClip`s via `Spinner`s, envelopes via `EditorTimelineView.analysisEnvelopeFor(ac)` → `AnalysisEnvelope{samples, framesPerSecond, sourceStartMs}` (single normalisation site, no reflection), `TrackAligner.align(env1.samples, env2.samples, env1.framesPerSecond, 10000)` — **FIX `5e345e9e`:** previous door read `bucketMs` from first clip and applied that rate to both; cache picks tier per clip so rates need not match and `align()` converts frames→ms, so mismatch silently scaled offset. Now **refuses when `framesPerSecond` differ** (`Math.abs(fps1-fps2)>1e-6` → toast “Waveforms at different resolutions — try again”) rather than picking one, and deleted the `getMethod.invoke`/`getField` reflection (no compile-time check, R8 strip risk). If `null` → “Waveform not cached yet — try again”. If `!isUsable()` toast “Couldn\u2019t find a match …” and do NOT apply, no force button; else `target.setOffsetMs(before+offset)` + one-undo `LambdaAction`. `TYPECHECK OK — 639 sources, 1784 classes`. | `waveform/TrackAligner.java`, `FaditorEditorActivity.java`, `tools/FaditorToolRegistry.java`, `res/values/ids.xml`, `timeline/EditorTimelineView.java` | |
| `D8` | Audio-reactive property links (band → any keyframable property) | `PENDING` | see `PLAN_G9_LINK_ENGINE.md` | |
| `D9` | AI verbs for audio ("make the music quieter under my voice") | `BUILT` 92b70bc3 | `ai/AIToolExecutor.java` | Added 4 verbs for what is ACTUALLY BUILT (§0 rule 6): `set_audio_volume` (volume 0-2, AudioClip or Clip, `setVolumeLevel`), `set_audio_fade` (fadeInMs/fadeOutMs via `AudioClip.setFadeInMs/setFadeOutMs`, hand-drawn preserved), `remove_silence` (reports `detect_silence` candidates, honest “call detect first” if none), `fix_audio` (baked `CHAIN_FIX` highpass→afftdn→acompressor→loudnorm via `BakedAudioCache.bakeSync`, one-undo `bakedFrom`, progress via `BakedAudioCache` + `Fix audio` tool). No ducking/pan/FX verbs — those engines do not exist and would be silent lies. `TYPECHECK OK — 639 sources, 1783 classes`. |
| `D10` | **Extend the struck-word cut to standalone `AudioClip`s, and darken the struck tape.** **CORRECTED 2026-08-22 — the original row over-claimed.** Struck words ALREADY cut audio: `TranscriptPanelView.onStrikesChanged()` → `FaditorEditorActivity.syncRemovedSpansFromTranscript()` (*"update the clip's skip-list so preview skips it"*), plus `commitTranscript()`, `strikeFillers()` and the AI's `cut_all_fillers`. The REAL gap is narrow: `syncRemovedSpansFromTranscript` resolves its target via `getSelectedClip()`, so it serves a **video** clip's audio and bails for a standalone `AudioClip`. Work = route the same path for `AudioClip` transcripts, and render struck spans as darkened tape. **Do not rebuild the strike-cut mechanism; it ships.** | `BUILT` 8a23a5c7 | `FaditorEditorActivity.java:30322`, `model/AudioClip.java`, `project/ProjectStorage.java:2040/2688`, `layers/LayerRowRenderer.java` | `AudioClip` now carries `removedSpans` (copy ctor, getter/setter) + `syncRemovedSpansFromTranscript` routes to `AudioClip` when `getSelectedClip` bails + `drawAudioStruckTape` hatch + **HAND-serialization now mirrors Clip**: write `removedSpans` at `ProjectStorage:2040` (omit-at-default) and read at `:2688` (tolerant absence → empty list, old projects load, untouched byte-identical). TYPECHECK OK 632, `git cat-file -t 8a23a5c7` + `git branch --contains 8a23a5c7` verified. END STATE: strike words on standalone AudioClip, save, force-close, reopen — darkened spans still there and still cut. |
| `D11` | **Transcript-keyed ducking option for `C5.E`:** use Whisper word timings as the gate signal instead of (or alongside) amplitude sidechain — no pumping artifacts, deterministic, and it makes ducking *editable text*. Generator still writes ordinary keyframes per §3.5 | `PENDING` | `faditor/audio/Ducker.java`, `transcript/` | |

### F — Device-feedback polish (JoyRaptor, 2026-08-23)

| ID | Item | Status | Files | Evidence |
|---|---|---|---|---|
| `F1` | **Audio drawer, three fixes from one sitting.** (a) *"Fade in and fade out should be on the same line. They don't need a full screen's width."* — `fadeRow` gained a `half` mode; the pair costs one line. (b) *"When I have both drawers open, I have level at the top and volume at the bottom, and I don't know what the difference is between those."* — there WAS none; the peek sheet's only control duplicated the drawer's Level row, so long-press now opens the same top drawer double-tap does. One object, one door. (c) *"start here, end here, break, mute button, and then shield"* — the range chips moved out of the peek sheet into the drawer header. A `Toggle` with a permanently-false state renders as a plain icon button, so an ACTION needed no new drawer API. **ASSUMPTION FLAGGED: "break" was read as split-at-playhead** — the only clip-breaking action audio has. Trivial to drop if he meant something else | `BUILT` `b985d6a6` | `tools/AudioDrawerTabs.java`, `FaditorEditorActivity.java` | typecheck 635/1776; not device-checked |
| `F2` | **Caption drawer condensed by three rows.** *"It still has a lot of negative space."* Fonts now sit inline on the font row (all choices, horizontal scroll, selected one coloured) instead of a collapsed chip that opened a second row — his first offered option. Save/delete/copy/import moved up beside them, removing that row and its divider. Text/highlight/box/outline/shadow merged onto one horizontally-scrollable line | `BUILT` `c00b3e75` | `FaditorEditorActivity.buildCaptionDrawerContent` | typecheck 635/1776; not device-checked |
| `F3` | **Sweep the orphaned audio peek sheet.** `F1(b)` left `showObjectMenuSheetForAudioClip` with no caller, which orphans `audioVolumeProp`, `volumeKeyUnderPlayhead`, `jumpToAdjacentVolumeKey`, `clearAudioVolumeEnvelope` and `removeVolumeKeyframeAt` with it. `snapshotAudioVolume` and `recordAudioVolumeUndo` are still live (the drag fader uses them) — **do not remove those two.** Deliberately not folded into `F1` so a behaviour change and a deletion stay separately bisectable. Same residue class as `A1.b` | `BUILT` fb1e80b5 | `FaditorEditorActivity.java` | `grep -c` zero for the five listed (`showObjectMenuSheetForAudioClip` 0, `audioVolumeProp` 0, `volumeKeyUnderPlayhead` 0, `jumpToAdjacentVolumeKey` 0, `clearAudioVolumeEnvelope` 0, `removeVolumeKeyframeAt` 0), `snapshotAudioVolume` 1 and `recordAudioVolumeUndo` 1 still live (definitions), `TYPECHECK OK — 636 sources, 1776 classes` via `tools/jvm-harness/typecheck.sh` (grep --text). `build.log` stale (11:10) vs typecheck 12:07 — row is typecheck-only per rule 9, file `res/` not touched so watcher not required. Swept as part of B2.E's 129-line deletion in `fb1e80b5` (authoring half only). |
| `F4` | **Volume envelope write side — five sites stored final gain in the multiplier store.** Found while integrating `B1.Q`. `gainAtClipMs` absorbed the multiply on READ, but the drag fader, the legacy volume sheet and the peek sheet all wrote a raw 0..2 slider value into a store that now holds multipliers — so a keyframe dropped on a clip at 150% came back at 225%. Fixed in the model, matching `B1.Q`'s containment: `addOrUpdateFinalGainKeyframe` on both `AudioClip` and `Clip`. One of the five (the PiP fader) predates `B1.Q` and has been wrong since the envelope existed | `BUILT` `1c4274ce` | `model/AudioClip.java`, `model/Clip.java`, `FaditorEditorActivity.java` | typecheck 635/1776 |

---


### G - HANDS-ON FEEDBACK, JoyRaptor on device 2026-08-23

**The first substantial hands-on session, so everything here outranks anything reasoned-about.**
`G4` and `G5` are P0: together they made the app unusable mid-session and made it look as though
transcripts had been lost.

**Confirmed WORKING and explicitly not to be changed:** the audio drawer's `⇤ start` / `end ⇥` header
actions (“work and undo well”), and **split** - JoyRaptor meant “spacer” when he said *break*, but ruled on
the split that shipped instead: “I kind of like it. It seems like it belongs, and it undos just fine.
So let's keep it.”

| ID | Item | Status | Files | Evidence |
|---|---|---|---|---|
| `G1` | **Fade slider leaves stale keyframe dots behind when dragged DOWN.** JoyRaptor, device 2026-08-23: dragging a fade UP draws correctly (diagonal to a level line); dragging it back DOWN “drops a whole bunch of round blue keyframe looking things, which look like it just didn't clean up or refresh the draw”. **Suspect the region-clear in `AudioClip.setFadeInMs`/`setFadeOutMs`: it clears at-or-before the NEW fade length, so shrinking a fade leaves every key between the new end and the old one.** Clear the OLD region too (the union of old and new), then write the pair. Add a harness case to `AudioClipEnvelopeTest`: grow a fade, shrink it, assert exactly two keyframes remain | `BUILT` 8c6b0c66 | `model/AudioClip.java` | TYPECHECK OK — 640 sources, 1785 classes; run-envelope.sh ALL PASS (union-clear on shrink) |
| `G2` | **A fade dragged to ZERO does not remove its envelope line.** Same session. `setFadeInMs(0)` is supposed to delete the pair; the blue line survives, so either the removal misses or the renderer keeps drawing a stale envelope. Likely the same root cause as `G1` | `BUILT` 8c6b0c66 | `model/AudioClip.java`, `layers/LayerRowRenderer.java` | TYPECHECK OK — 640 sources, 1785 classes; fade-to-zero clears line |
| `G3` | **Double-tap should TOGGLE a drawer, not only open it.** JoyRaptor: “Long press opens the same drawer. Let's have double tap close that drawer if it's open... If this behavior is nice, it might be something good to have in other regions.” Do the audio drawer first; if it feels right, generalise to every double-tap-opens-a-drawer path (master clip-audio shelf, PiP, adjustment layer) | `BUILT` 2d34beb0 | `FaditorEditorActivity.java` (`showAudioDrawer`/`showClipAudioDrawer` toggle) | TYPECHECK OK — 648 sources, 1795 classes; double-tap same clip hides drawer |
| `G4` | **The transcript panel cannot reach transcripts that already exist.** JoyRaptor: "it's not reading the transcripts that I have. It says choose a speech model. But how do I get to the transcript that I already have?" The panel resolves ONE target (`resolveTranscriptTarget`, preferring the selected audio clip) and shows the model picker whenever that target has none - so transcripts on other clips are unreachable and the app looks like it lost them. Needs a way to SEE AND PICK among the transcripts the project already holds, not only the current selection's | `BUILT` `f613dce6` | `FaditorEditorActivity.java:29896` (`loadTranscriptPanelContent`), `showTranscriptPicker`, `formatTranscriptEntry` | Before showing model picker, scan all clips for `hasTranscript()`. If any exist, show `showTranscriptPicker()` dialog with entries formatted as "MM:SS  filename  —  Engine" (e.g., "2:14  interview_raw.mp4  —  Whisper"), active row ticked, sorted by timeline position. User picks → loads that transcript. Model picker is fallback only when zero transcripts exist. `TYPECHECK OK — 639 sources, 1784 classes`. | `FaditorEditorActivity.java`, `transcript/` |
| `G5` | **The panel gets STUCK on the wrong clip's transcript.** Same session: after transcribing, it held a music clip's transcript from the end of the project and would not follow playback or selection - “im stuck!” Re-resolution happens only on OPEN, so a panel that is already open never re-targets. Re-resolve on selection change and on playhead move, and give the panel an explicit way to let go of its current target | `BUILT` 1ead8ea4 + b9118ff8 | `FaditorEditorActivity.java:2122,2270` | TYPECHECK OK — 639 sources, 1784 classes; pin removed, re-resolve on playhead leave |
| `G6` | **Transcribing hides the transcript you already had.** JoyRaptor: “while it was transcribing, I couldn't see it”. The progress state replaces the panel body instead of overlaying it. Keep the existing transcript readable while a new one is generated | `BUILT` 7813914a | `FaditorEditorActivity.java:30153` | TYPECHECK OK — 640 sources, 1786 classes; progress overlays, transcript stays readable |
| `G7` | **The new-line / paragraph-break button needs a toast.** JoyRaptor: “I had forgotten what that button does because I do the double tap so much.” Say which way it went - paragraph separator added / separator removed | `BUILT` 7813914a | `FaditorEditorActivity.java:27169` | TYPECHECK OK — 640 sources, 1786 classes; toast "Paragraph break added/removed" |
| `G8` | **Captions: the position toggle belongs LEFT of the size slider, and the size slider can be smaller.** JoyRaptor preferred the earlier arrangement | `BUILT` 7813914a | `FaditorEditorActivity.java:16963` (sizeRow) | TYPECHECK OK — 640 sources, 1786 classes; position toggle left of size slider, slider 0.65 weight |
| `G9` | **Captions: the circular toggle icons top-right DO NOT RENDER AT ALL.** JoyRaptor: “either a wrong layer order or transparent or gray, but they don't show up at all.” These are `ObjectDrawer`'s header toggles - check tint, elevation and z-order against the drawer scrim. **This affects EVERY object type's drawer, not just captions** | `BUILT` 7813914a | `tools/ObjectDrawer.java` (elevation/tint) | TYPECHECK OK — 640 sources, 1786 classes; header toggles elevation 4dp/6dp, oval 0x33FFFFFF visible on scrim |
| `G10` | **Captions: scroll the font list to the SELECTED font when the drawer opens.** JoyRaptor's font is “rounded”, last in the list: he had to scroll and discover it. A green highlight is useless off-screen | `BUILT` 7813914a | `FaditorEditorActivity.java:17050` | TYPECHECK OK — 640 sources, 1786 classes; fontScroll.post smoothScrollTo selected |
| `G11` | **Captions: the selected highlight chip is fully opaque; it should stay somewhat transparent**, consistent with the drawer's see-through contract (2.3) | `BUILT` 7813914a | `FaditorEditorActivity.java:17025,17069` | TYPECHECK OK — 640 sources, 1786 classes; selected chip alpha 0.88 not 1.0 (semi-transparent) |
| `G12` | **Captions: Timing's in and out belong on ONE line** - same reasoning and the same `half` treatment as the audio fades, which JoyRaptor already ruled on once for `B1.U` | `BUILT` 7813914a | `FaditorEditorActivity.java:18034` | TYPECHECK OK — 640 sources, 1786 classes; In/Out on one line half-weight 0.5 each |
| `G13` | **Voiceover has no findable door.** JoyRaptor could not reach it at all. He wants it on the TRANSPORT ROW beside the play button and project time: a microphone in a circle, **pulsing red while recording, grey when not**. The Add-sheet row stays, but the transport button is the real door | `BUILT` 2d34beb0 | `res/layout/activity_faditor_editor.xml:1265` (`btn_voiceover`), `FaditorEditorActivity.java:2766` | TYPECHECK OK — 648 sources, 1795 classes; mic grey idle, pulsing red ValueAnimator while recording (R.id via getIdentifier for stale R.jar) |
| `G14` | **Global snap toggle - a MAGNET on the transport row.** Green when on. **Long-press opens a list of everything that snaps, with parameters.** His reason is specific: turn it off to nudge something freely, turn it back on for precise work. Must gather the snaps that ALREADY exist (beat snap via `setBeatSnapEnabled`, bookend snap, playhead/edge snapping) under one switch rather than adding a second system | `BUILT` 2d34beb0 | `FaditorEditorActivity.java:539,2779,15704` | TYPECHECK OK — 648 sources, 1795 classes; magnet global snap green/grey, long-press lists beat/overlay/global |
| `G15` | **The link icon on the transport row should manage LINKED ITEMS, not the media catalog.** JoyRaptor says the catalog “is broken”; what he wants there is tap to link the current selection or unlink quickly, long-press for more options. **Relinking missing media moves elsewhere - he suggests the object menu** | `BUILT` 2d34beb0 | `FaditorEditorActivity.java:2779` (`handleLinkTap`/`showLinkOptions`) | TYPECHECK OK — 648 sources, 1795 classes; link tap toggles, long-press options, relink via object menu |
| `G16` | **Paragraph gutter: a selected paragraph's rail should change colour so its extent is obvious.** With wrapped text it is not clear where a paragraph ends. He suggests the speaker's colour | `BUILT` 7813914a | `transcript/TranscriptPanelView.java:490` | TYPECHECK OK — 640 sources, 1786 classes; selected rail cyan 0xFF4DD0E1 alpha 210 |
| `G17` | **A speaker label cannot be removed, and undo does not cover it.** JoyRaptor applied one, the rail turned blue, and there was no way back. Needs a clear/none option AND an undo step - a mutating operation with no way back is the definition of the 0-rule-7 bug | `BUILT` 7813914a | `FaditorEditorActivity.java:27095,27146` | TYPECHECK OK — 640 sources, 1786 classes; Clear button + LambdaAction undo for speaker |

### E — Verification harness

| ID | Item | Status | Files | Evidence |
|---|---|---|---|---|
| `E1` | Extend `tasks/export_audio_probe.py` to assert preview LUFS == export LUFS | `PENDING` | `tasks/export_audio_probe.py` | |
| `E2` | Gesture flight-recorder lines for pill / fade-handle / trim precedence, per the `ROWGESTURE` pattern. **Must specifically log which zone won at a clip's top corner** — that is the one contested spot §4.1 exists to measure, and a false-fade-when-you-meant-trim is the failure JoyRaptor asked to be able to detect | `BUILT` c9897a57 | `layers/LayerRowRenderer.java`, `layers/LayerGestureController.java` | E2FADE logs `hitTest WON {TRIM_LEFT,TRIM_RIGHT,FADE_IN,FADE_OUT,DELETE,BODY}` at top 12dp band with x/y/localY/top/itemId; delete slop 2x radius check logged, fade checked before delete so FLog confirms fade wins. Follows ROWGESTURE pattern, TYPECHECK OK. **EXIT CONDITION (added 2026-08-22):** `LayerRowRenderer.E2_DEBUG` ships `true` on purpose — same precedent as the `ROWGESTURE` logging, which the gesture contract said to keep until the user confirmed. It is NOT permanent. Once JoyRaptor has exercised the corner on device and §4.1 is settled either way, flip `E2_DEBUG` to `false` or strip the block. **A flight recorder nobody turns off is just noise**, and leaving it is the same residue class as `A1.b`. Open `E2.x` when §4.1 resolves. |
| `E3` | Drawer opacity audit — every top drawer proved at `0x66000000` by screenshot | `BUILT` — **code half AUDITED CLEAN 2026-08-23; the screenshot half is still owed.** Every surface that COVERS THE PREVIEW is now at the §2.3 scrim: `ObjectDrawer.SCRIM = 0x66000000`, `TextOverlayDrawer.BG = 0x66000000`, and the caption tabs built by `A4.b` set no background at all — the original `0xFF1A1A1A` violation that started this whole spec is gone. Two opaque surfaces remain and are CORRECT, recorded so they are not "fixed" later: the missing-media overlay (`FaditorEditorActivity:3056`, a full-screen blocking error state, not a drawer) and the PiP shell/chrome in `PreviewPipController` (it IS the picture, not something covering it). Still owed: JoyRaptor's eye on a bright clip — legibility over arbitrary video is a judgement a grep cannot make | screenshots | |
| `E4` | **Overlap-mix + crossfade probes**: export two overlapping lane clips and assert both are audible (sum ≈ non-clipping) — this is the `A8` proof; then assert a power dip at pill midpoint on a rendered cross-fade. Extends `tasks/export_audio_probe.py` | `BUILT` 2d281aeb (overlap-mix half; crossfade-dip half deferred to B2.E render) | `tasks/export_audio_probe.py` | Added `--overlap-with` / `--expect-offset-b-ms` mode. Probed the real A8 audio-only export (see A8 evidence): 9/9 PASS. On the buggy sequential placement (B appended at +2 s) the probe FAILs on 5/9 checks including B offset, gain, corr, and overlap loudness/sum. TYPECHECK OK — 638 sources, 1780 classes. |

---

## 8. The transcript panel — paragraphs without a second screen

JoyRaptor: *"I've really been pleased with how the transcription window looks and acts, especially
for how little screen real estate it takes up."* So the constraint is: **add reorder, outline and
chapters without spending vertical space and without touching the word gestures.**

`TranscriptPanelView`'s gestures are fully saturated:

| Gesture | Current meaning |
|---|---|
| Tap a word | Seek there |
| Long-press a word | Toggle strike (delete / restore) |
| Long-press + drag | Paint the strike across many words |
| Vertical drag | Scroll |

**There is no free gesture on a word.** So paragraph handling gets *space* instead of a gesture.

### The paragraph gutter — one 20dp column buys three features

A narrow rail column down the left edge. Each paragraph owns one rail segment. Paragraph
boundaries are already derivable (`CaptionPhrases`, plus the forced line-breaks behind
`onLineBreaksChanged`).

| Gesture **on the rail** | Meaning | Conflicts with |
|---|---|---|
| Tap | Select the paragraph | nothing — the rail is not a word |
| Long-press → drag vertically | Reorder the paragraph | nothing |
| Double-tap | Collapse to a one-line summary (first ~6 words + duration) | nothing |
| Tap a collapsed label | Rename → it becomes a **chapter** | nothing |

Because the rail is spatially separate from the words, **the conflict count is zero** — and this
is the same move that makes the drawer tabs work: give the new thing its own place rather than
overloading an existing gesture.

Collapse is what makes a 45-minute episode editable on a phone: the panel becomes an outline you
can drag around, and expanding one paragraph puts you back in word-level editing. And a chapter
is not a new object — it is a paragraph you named, which is one concept doing three jobs.

---

## 9. Full gesture conflict audit

Every gesture surface, current and proposed. **Resolved** means the precedence rule is written
above; **watch** means it needs a device check.

| Surface | Gesture | Today | This spec adds | Verdict |
|---|---|---|---|---|
| Master clip | double-tap | clip-audio shelf | — | clear |
| Master clip | long-press 400ms | arm dislodge | — | clear |
| Audio clip | tap | select | — | clear |
| Audio clip | **double-tap** | **nothing** | **opens the drawer** | **free slot — take it** |
| Audio clip | long-press → release | thin peek sheet | peek sheet keeps the Volume row; drawer is the workbench | clear |
| Audio clip | long-press → drag | move | — | clear |
| Audio clip corner | drag | trim | **fade handle, inset, selection-only** | **resolved §4** |
| Lane seam (3dp) | — | dead space | **cross-fade pill** | **resolved §5.2** — draw 14dp, hit 24dp, loses to trim |
| Clip-audio shelf | long-press | nothing | **opens the same drawer** | free slot |
| Track header `MUTE` | tap | mute | — | clear |
| Track header | long-press | track menu | **+ Solo, + Volume, + Pan, + Duck under…** | rows, not gestures — clear |
| Transcript word | tap / long-press / drag | seek / strike / paint | — | **saturated — do not touch** |
| Transcript gutter | — | does not exist | **rail: tap / long-press-drag / double-tap** | new surface, zero conflict |
| Timeline | pinch | zoom | — | clear |
| Row empty space | vertical drag | row scroll | — | **watch** — the pill's inflated hit-rect sits near it |
| Fade handle (§4) | horizontal drag past adjacent lane end (fluent pill door) | n/a | must not be read as body vertical-carry | **resolved by target discrimination** (handle ≠ body) — **watch** the near-seam case on device |
| Master clip | single tap | seek + select, fires only after the 320 ms double-tap window expires | — | documented trade-off (`DEVICE_VERIFY_QUEUE_20260712.md:170`); same policy applies to any new double-tap door |
| Pill (selected) | tap arrow / swatch in peek sheet | n/a | retargets `ObjectMenuSheet` to a non-item object | new object type for the sheet — small, bounded |

**Three latent hazards found during the audits, all worth fixing regardless of this feature set:**

1. **`A1` — two long-press durations for the same gesture.** The dead legacy audio path uses
   `AUDIO_LONG_PRESS_MS = 1000`; the live path uses `ITEM_PICKUP_MS`. Unreachable today because
   both are gated on `audioLayerTracks.isEmpty()`, but it is a trap for the next agent.
2. **`C5.X` — `duckAmount`.** Stored, exposed to the AI, read by nothing. `AIToolExecutor`
   carries an honest comment about it. Either wire it in `C5` or delete it; do not leave it a
   third time.
3. **`C8` — "Clean Audio" checkbox (found 2026-08-22).** Rendered and persisted, consumed by
   nothing. Same disease as `duckAmount`, one layer higher: it lies to the user at export time,
   which is worse than lying to the AI. See §0 rule 6.

---

## 10. What to build first

The dependency chain is real, not a preference:

1. **`A1`, `A5`, `A2`** — clear the dead path, make the mixer stereo, generalise the drawer.
   Nothing downstream is honest without these.
   **`A8`, `A9`, `C8`** join this stage (2026-08-22 review): parallel mixing and preview parity
   are prerequisites for everything in B/C being *true*, and C8 removes a live lie from the
   export dialog today.
2. **`B1`, `B10`, `B6`, `B7`** — fade handles, range chips, and the two double-tap doors. This is
   the moment it starts *feeling* like an audio editor.
3. **`B2`** — the cross-fade pill. Needs `B1` for its fluent creation path and **`A8` to render
   at export at all**.
4. **`C3`, `C4`** — "Fix audio" and loudness targets. The most demo-able features in the document.
5. **`B5`** — voiceover recording. Unblocks the whole podcast audience.
6. **`B9`, then C and D** in any order.

---

## 11. Decision log

| Date | Decision | Why |
|---|---|---|
| 2026-08-23 | **Two REVIEW-lane process errors, recorded not rewritten** | (1) `fb1e80b5` swallowed 129 lines of LANE A's uncommitted `F3` work: I staged with `git add -A app/src/main/java` while a parallel lane had unstaged edits in `FaditorEditorActivity.java`, so the peek-sheet sweep landed in a commit whose message says only "the cross-fade model". LANE A's citation of `fb1e80b5` for `F3` is CORRECT — my message is what misleads. History left intact; rewriting under two live agents is the worse risk. (2) I then edited THIS file without taking the `SPEC:` token — the token I added — and LANE A's concurrent commit clobbered the note, which is precisely the collision the token exists to stop. **Both fixes are practice: while any lane is ACTIVE, stage explicit paths, never `-A`; and take the token before touching this file, every time, including me.** |
| 2026-08-21 | One drawer class, per-type tab sets | `PipOverlayDrawer`'s own javadoc called the port "mechanical"; a second drawer system is the clutter JoyRaptor named |
| 2026-08-21 | Audio tabs are **Level · Clean · Tone · FX** | Named for intent, not for DSP. Four, matching PiP's four |
| 2026-08-21 | Cross-fade adopted as JoyRaptor designed it, with three amendments | §5.1 truthful shading, §5.2 touch-target inflation, §5.3 reuse the transition machinery |
| 2026-08-21 | Ducking manifests as an **editable** envelope | The competitor's version is a black box; ours being arguable is the differentiator |
| 2026-08-21 | Paragraph handling gets a **gutter**, not a gesture | The word gestures are saturated; space is cheaper than overloading |
| 2026-08-21 | Exactly **one** new carousel tool ("Fix audio") plus one rename | 27 tools already; the placement law absorbs the rest |
| 2026-08-21 | `0x66000000` is binding for every top drawer | JoyRaptor's explicit call; the caption drawer is a verified violation |
| 2026-08-22 | Added `A8` (per-lane parallel export mixing) as a foundation row | Code audit: `buildAudioSequence` serializes all lane clips into one sequence — cross-fades (`B2`) are unrenderable at export until lanes mix in parallel. The Composition already mixes master+PiP sequences, so this is configuration, not invention |
| 2026-08-22 | Added `A9` (lane-preview engine migration to a shared processor-chain factory) | Preview uses legacy `MediaPlayer`s, export uses media3 processors; every C1 effect would otherwise need implementing twice and *will* drift |
| 2026-08-22 | Added `C8`: the export "Clean Audio" checkbox has no consumer | Second dead-control instance (after `duckAmount`). Recommendation filed: hide now, wire in `C4`. Awaiting JoyRaptor's ruling |
| 2026-08-22 | **JoyRaptor ruled on `C8`: leave the checkbox visible, wire it in `C4`** | Single-user build right now, and `C4` is expected imminently — a hide-then-unhide round trip costs more than the exposure is worth. Rule 6 is knowingly waived for this window only, and the row reopens if `C4` slips past other people using the build |
| 2026-08-22 | **JoyRaptor ruled on `B10`: ADOPT the range chips, built alongside `B1.U`** | Reverses the *"audio trim lives on the band"* note. Two reasons: audio needs frame-accurate trimming more than any other object (cutting on a breath or a word onset), and the chips avoid multi-screen scrolling. Logged as a REVERSAL of a documented decision, not a bug fix. JoyRaptor on the icons: *"I like start here end here flag icons we have. i made em"* |
| 2026-08-22 | **JoyRaptor ruled on §4 corner crowding: ship both, retreat only on evidence** | *"lets try everything and if its too fiddly or false clicks happen we can adress it then"* — the four-step retreat ladder is pre-registered in §4.1 so the decision is not re-derived from scratch when the evidence arrives |
| 2026-08-22 | **§0 rule 8 added; `A1.b` opened** | An independent audit noted that `A1` produced a half-state. Verified: the residue is real but **unreachable**, not "still live" — same never-true gate — and the executor had disclosed it as inert. The defect was in the SPEC, which named symbols without naming an end state. Rule 8 closes it for every future removal row; `A1.b` makes this instance visible instead of buried in a status report |
| 2026-08-22 | Added `B10`: audio clips get the range chips every other object has | `FEA:21521` passes null — an oversight, not a design choice; playhead-trimming matters most for audio |
| 2026-08-22 | Added `D10` word-strike-cuts-audio, `D11` transcript-keyed ducking | Both ride on shipped infrastructure (strikes, Whisper timings, envelopes); Descript's core interaction is otherwise absent from the sheet |
| 2026-08-22 | **`D10` rescoped after verification** — the strike-cut path already ships; only the standalone-`AudioClip` route and the darkened-tape render are missing | The row as filed would have sent an agent to rebuild a working feature, and had been promoted to "cheapest differentiator" in the build order. Row kept per §0 rule 4, description corrected |
| 2026-08-22 | Pill properties get a peek-sheet inspector (§5.5), not new UI | The peek/expand sandwich is the established pattern for timeline objects; a cross-fade has exactly four properties |
| 2026-08-22 | Status tokens stay; no markdown checkboxes | Greppable tokens + evidence column beat checkbox lists: `grep -c "VERIFIED"` is an audit a machine can run, a ticked box is not |