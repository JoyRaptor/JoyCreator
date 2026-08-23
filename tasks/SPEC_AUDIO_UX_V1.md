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
| `A1.b` | **Sweep A1's disclosed residue.** All unreachable behind the same never-true `audioLayerTracks.isEmpty()` gate, so this is tidiness, NOT a live bug — do not treat it as urgent. (a) audio-trim drag path: `hitTestAudioHandle`, `Drag.AUDIO_LEFT_HANDLE` / `AUDIO_RIGHT_HANDLE`, `doAudioTrimDrag` (`EditorTimelineView:8682`), `audioTrimDrag*` fields — its painter `drawAudioTrimHandles` is already gone, so nothing draws what it would drag. (b) `selectedAudioIndex` + its dead fallback branch in `getSelectedAudioIndex()` (the live path derives from `LayerGestureController` and must keep working). (c) orphaned listener methods `onAudioBandDoubleTapped` / `onAudioClipSelected` / `onAudioTrimChanged` / `onAudioTrimFinished` — no emitters remain. **Touches `FaditorEditorActivity`, so claim the lane in `LANES.md` first.** END STATE: zero references to any listed symbol; `typecheck.sh` green; audio select/trim on the unified rows unchanged on device | `PENDING` | `timeline/EditorTimelineView.java`, `FaditorEditorActivity.java` | |
| `A2` | **Rename `PipOverlayDrawer` → `ObjectDrawer` and decouple the Adjust tool-light. SCOPE IS DELIBERATELY BORING — no new tab sets, NO behaviour change.** Re-scoped 2026-08-22 after reading the class: the chrome is ALREADY payload-agnostic (its javadoc: *"it knows about Tabs and an icon row, not about compositing"*; `show(List<Tab>, List<Toggle>)` already takes arbitrary tabs). So this is a rename, not a refactor. **`PipDrawerTabs` KEEPS its name** — it really is PiP content. **THE ONE TRAP:** `ensurePipDrawer()`'s height listener hardcodes `setAdjustToolActive(h > 0)` (`FaditorEditorActivity:22869`). That instance is reused for every type, so the moment a fourth caller exists, opening an AUDIO drawer would light the carousel's Adjust/FX tool. Replace the hardcode with a per-caller visibility hook. **END STATE:** zero identifiers matching `/[Pp]ipDrawer|PipOverlayDrawer/` outside `PipDrawerTabs` and its PiP call site; the Adjust light is caller-supplied, not baked into the drawer; scrim still `0x66000000`; `reflowPreviewUnderDrawer` and `setTopBarHiddenForDrawer` still wired; and **all three existing drawers (PiP, image, adjustment) behave IDENTICALLY on device — this row adds no user-visible change at all.** Audio tab CONTENT is `C1.U`, not this row; doors are `B6`/`B7`. | `BUILT` c801aaf1 | `tools/ObjectDrawer.java` (rename), `FaditorEditorActivity.java:22860-22875`, `:22193` | Verification plan (pre-commit): open PiP, image, adjustment drawers → confirm tabs/toggles/scrim 0x66000000/preview-shift (reflowPreviewUnderDrawer)/top-bar hidden identical; Adjust lights for adjustment only, not PiP/image. Checked via code inspection + grep zero PipOverlayDrawer outside PipDrawerTabs + typecheck OK 632 + build.log BUILD SUCCESSFUL; could not check actual drawer open/close/tabs/preview-shift/top-bar/Adjust light without device, so BUILT not VERIFIED. END STATE: zero pipDrawer/PipOverlayDrawer outside PipDrawerTabs/PiP site, Adjust light caller-supplied single funnel, scrim 0x66000000, reflow still wired, three drawers identical. **HARDENING NOTE for `B6`/`B7` (the 4th caller):** `objectDrawerLightAdjust` is an INSTANCE FLAG set before `show()`, and all four current callers set it `true`. It is therefore correct today but **forgettable** — a new caller that omits the assignment silently inherits the PREVIOUS caller's value, so opening an audio drawer right after an adjustment drawer would light the wrong tool. When `B6`/`B7` add the audio caller, promote it to a `show()` PARAMETER so it cannot be skipped. Also: two stale `{@link PipOverlayDrawer}` javadoc refs remain in `PipDrawerTabs` (`:27`, `:655`) — broken links, harmless to the build, sweep with `A1.b` |
| `A3` | `MAX_HEIGHT_FRACTION` 0.55 / 0.75 branch for audio-only projects | `PENDING` | `tools/ObjectDrawer.java` | |
| `A4.a` | **Make the caption drawer SEE-THROUGH. This is the whole of JoyRaptor's original complaint and it is a one-line fix.** Split out of `A4` on 2026-08-22 so the thing he actually asked for is not held hostage to a 300-line migration. `buildCaptionDrawerContent` sets `root.setBackgroundColor(0xFF1A1A1A)` — fully opaque — the single `0xFF1A1A1A` in the file. Change to `0x66000000` (§2.3, same scrim as `ObjectDrawer`) and give the drawer's own text/labels `setShadowLayer` contrast the way `PipDrawerTabs` does, so they stay legible over arbitrary video. **Verify by eye against a bright clip, not by reading the constant.** | `BUILT` a65983f8 | `FaditorEditorActivity.buildCaptionDrawerContent` | root scrim 0x66000000 + setShadowLayer 3f*d on labels/chips (styleDrawerChip + sizeVal + Font/Highlight/Motion/Text/Highlight + Box/Outline + Shadow), PipDrawerTabs pattern, BUILD SUCCESSFUL SM-N960U (eye verify vs bright clip owed). |
| `A4.b` | **Full caption-drawer migration onto `ObjectDrawer`** (tabs: Style · Timing · Position per §2.1). Deferred — `A4.a` already removes the user-visible harm. Real scope, measured 2026-08-22: `buildCaptionDrawerContent` is **244 lines**, plus a layout-defined `caption_drawer` block in `activity_faditor_editor.xml:2463+` with its own grab/header/close/content children and its own show/hide animation, all of which the migration deletes. **Do this when someone is already working in captions for another reason — not as a standalone errand.** END STATE: no `caption_drawer` ids in the layout, captions open through `ObjectDrawer` like every other type, and the style/size/font/animation/colour controls all still work | `PENDING` | `FaditorEditorActivity.java`, `res/layout/activity_faditor_editor.xml:2463+` | |
| `A5.E` | Stereo pan: replace mono `VolumeAudioProcessor` gain with a stereo gain/pan processor, preview + export | `PENDING` | `export/VolumeAudioProcessor.java`, `export/ExportManager.java`, `compositor/MasterPlaybackEngine.java` | |
| `A5.U` | Pan slider in the Level tab | `PENDING` | `tools/AudioDrawerTabs.java` | |
| `A6` | Project sample-rate policy: one rate, everything resampled in | `PENDING` | `export/ExportManager.java` | |
| `A7` | Audio parameters carried on a shared type so a *video clip's* audio gets the identical drawer (§2.2) | `PENDING` | `model/AudioClip.java`, `model/Clip.java` | |
| `A8` | **Per-lane parallel export mixing.** Today `buildAudioSequence` flattens ALL audio clips into ONE sequential `EditedMediaItemSequence` with silence-gap fillers — two overlapping lane clips cannot both sound at export. Fix: one sequence per AUDIO lane (the Composition already mixes master + PiP-overlay sequences in parallel, so the mechanism is proven). **Hard prerequisite of `B2.E`: a cross-fade between lanes is unrenderable without it** | `PENDING` | `export/ExportManager.buildAudioSequence` (`:2021-2126`) | |
| `A9` | **Lane-preview engine migration.** Audio-clip preview runs on a fleet of legacy `android.media.MediaPlayer`s (`FaditorEditorActivity:611`) while export runs media3 processors — parity by discipline only. Migrate lane preview to ExoPlayer instances sharing ONE processor-chain factory with `ExportManager`, so every effect added in C1 is heard identically in preview for free | `PENDING` | `FaditorEditorActivity.java`, `compositor/MasterPlaybackEngine.java`, new `compositor/AudioChainFactory.java` | |

### B — Table stakes

| ID | Item | Status | Files | Evidence |
|---|---|---|---|---|
| `B1.E` | Fade in / out written as two envelope keyframes | `VERIFIED` (built d93a5db8) — **JoyRaptor, device, 2026-08-22: the fade handles GRAB AND WORK.** §4.1 FUNCTIONAL TRIGGERS DID NOT FIRE: no false trim-vs-fade clicks reported, no zooming-to-hit-a-corner. So the §4.1 retreat ladder is NOT invoked and the geometry stands. **One objection recorded, cosmetic only:** JoyRaptor — *\"they are kind of ugly but they work, and i dont have a better idea for visuals right now\"*. Tracked as `B1.V` (visual polish), NOT as a §4.1 trigger — the two must not be conflated | `model/AudioClip.java` (via `AudioClip.VolumeKeyframe` envelope), `layers/LayerGestureController.java` | Envelope already exists and exports correctly; B1.E writes TWO keyframes (0→0, fadeDur→1 and dur-fadeDur→1, dur→0), one-undo via `LayerGestureController.armFade` + `FaditorEditorActivity.onGestureFinished` FADE handling. |
| `B1.U` | Fade drag handles, geometry exactly per §4 | `VERIFIED` (built d93a5db8) — **JoyRaptor, device, 2026-08-22: the fade handles GRAB AND WORK.** §4.1 FUNCTIONAL TRIGGERS DID NOT FIRE: no false trim-vs-fade clicks reported, no zooming-to-hit-a-corner. So the §4.1 retreat ladder is NOT invoked and the geometry stands. **One objection recorded, cosmetic only:** JoyRaptor — *\"they are kind of ugly but they work, and i dont have a better idea for visuals right now\"*. Tracked as `B1.V` (visual polish), NOT as a §4.1 trigger — the two must not be conflated | `layers/LayerRowRenderer.java`, `layers/LayerGestureController.java` | Trim outer 16dp full height, fade top 12dp×20dp inboard of trim, never overlapping, selection-only, trim wins. `ItemZone.FADE_IN/_OUT`, `TRIM_WIDTH_DP 16`, `FADE_W 20 H 12`, triangle draw `drawFadeHandles`, `armFade` + `FADE_*` drag→envelope, TYPECHECK OK 632. |
| `B1.V` | **Fade-handle visual polish — JOYRAPTOR'S DIRECTION 2026-08-22: round the wedge points.** He confirmed the current look already reads as a ramp line plus wedge handles and that the wedges are the ugly part: *"perhaps if the wedges had there points rounded a little?"* **This is a REPAINT ONLY** — §4 hit geometry (top 12dp × 20dp inboard of trim) must not move, or `B1.U`'s device verification is void. `drawFadeHandles` (`LayerRowRenderer:2373`) builds two filled 3-point `Path` triangles; the whole change is a `CornerPathEffect` on the paint. **TWO TRAPS, both already documented in this repo:** (1) `lessons.md` — *"Avoid allocating MaskFilter/Shader objects inside per-frame render paths"* — `PathEffect` is the same class of object and this is a draw path, so CACHE it in a field keyed on radius+density, never `new` it per frame. (2) `itemSelectionPaint` is SHARED; the method already saves/restores colour, style and strokeWidth — it must save/restore `pathEffect` too or every other path drawn with that paint gets rounded corners. Start at ~2dp radius and let JoyRaptor look. | `BUILT` c559774c | `layers/LayerRowRenderer.java:2373` | REPAINT ONLY: CornerPathEffect 2dp cached (fadeHandleCornerEffect, keyed radius+density) + fadeHandlePath reuse, pathEffect saved/restored on itemSelectionPaint, trim/fade/delete geometry untouched, §4.1 retreat untouched, BUILD SUCCESSFUL SM-N960U 2dp rounded wedges (hit geometry unchanged, needs device eye). |
| `B2.E` | Cross-fade model: pill object, direction, colour, span | `PENDING` | new `model/AudioCrossfade.java`, `export/ExportManager.java` | |
| `B2.U` | Pill render + gestures per §5, mirroring the transition pattern | `PENDING` | `timeline/EditorTimelineView.java`, `layers/LayerRowRenderer.java` | |
| `B2.U2` | `+ Cross-fade` in the Level tab; fade-handle-drag creation (needs `B1.U`) | `PENDING` | `tools/AudioDrawerTabs.java` | |
| `B2.U3` | Pill peek inspector per §5.5 (direction, colour swatches, duration, delete) | `PENDING` | `ObjectMenuSheet.java`, `FaditorEditorActivity.java` | |
| `B3` | Solo — a row in the track header long-press menu, ring on the mute glyph | `PENDING` | `layers/LayerRowRenderer.java`, `FaditorEditorActivity.java` | |
| `B4` | Level meters — 3dp gutter bar per track + a master meter in the preview corner | `PENDING` | `layers/LayerRowRenderer.java`, `compositor/MasterPlaybackEngine.java` | |
| `B5.E` | Voiceover capture: punch-in record against playback | `PENDING` | new `faditor/audio/VoiceoverRecorder.java` | |
| `B5.U` | "Record voiceover" row in the Add sheet | `PENDING` | `AddAssetBottomSheet.java` | |
| `B6` | Audio clip **double-tap** opens the four-tab drawer (today: nothing) | `PENDING` | `FaditorEditorActivity.java:14115` | |
| `B7` | **Long-press the clip-audio shelf** → the same four-tab drawer (§2.2) | `PENDING` | `timeline/EditorTimelineView.java`, `FaditorEditorActivity.java` | |
| `B8` | Rename carousel "Audio" → "Extract audio", re-icon `call_split` | `BUILT` dccea944 | `tools/FaditorToolRegistry.java`, `res/values/strings.xml` | BUILD SUCCESSFUL 2026-08-22 15:32 + installed SM-N960U (strings.xml touched, not just typecheck); icon `equalizer`→`call_split`, label `Audio`→`Extract audio`. |
| `B9` | Audio-only project mode: waveform-dominant layout, `.m4a`/`.wav`/`.mp3` export. Engine note: verify an empty master spine produces a valid `Composition` (export paths assume a master sequence today) | `PENDING` | `FaditorEditorActivity.java`, `export/ExportManager.java` | |
| `B10` | **Give audio clips the range chips.** **JOYRAPTOR RULED 2026-08-22: ADOPT — build it alongside `B1.U`, do not sequence it after.** `showObjectMenuSheetForAudioClip` passes `rangeChips = null` (`FEA:21521`), so audio is the only object without `⇤ Start here` / `End here ⇥`. **TWO chips, not three** — `↔ Span whole` is meaningless for audio (a clip's length IS its sound; it cannot stretch without looping or time-stretch). Mechanical: pass the two suppliers, reuse `ic_marker_flag_start` / `ic_marker_flag_end`. Reverses the *"audio trim lives on the band"* note above the call site — that note must be UPDATED, not silently contradicted | `BUILT` d93a5db8 | `FaditorEditorActivity.java:21521` | TWO chips only: `⇤ Start here`/`End here ⇥` via `setAudioRangeEdgeAtPlayhead`/`setAudioRangeEdgeAtMs` (offset+inPoint/outPoint trim, one-undo, invalid-range toast), comment above call site UPDATED to REVERSAL note, `LayerGestureController` fade handles ship together per §4.1 retreat ladder. |

### C — Sounds professionally produced

| ID | Item | Status | Files | Evidence |
|---|---|---|---|---|
| `C1.E` | Real-time FX chain: EQ · compressor · limiter · gate · de-esser · de-hum, shared preview/export | `PENDING` | new `faditor/audio/fx/`, `export/ExportManager.java` | |
| `C1.U` | Level / Clean / Tone / FX tab contents | `PENDING` | new `tools/AudioDrawerTabs.java` | |
| `C2.E` | Baked chain: `afftdn` denoise, two-pass `loudnorm`, revertible cache | `PENDING` | new `faditor/audio/BakedAudioCache.java` | |
| `C2.U` | Progress + "Revert to original" affordance | `PENDING` | `tools/AudioDrawerTabs.java` | |
| `C3` | **"Fix audio"** one-tap chain + carousel tool | `PENDING` | `tools/FaditorToolRegistry.java`, `faditor/audio/` | |
| `C4` | Loudness targets in the Export sheet, measured LUFS before/after. Analysis pass via `ffmpeg -af ebur128` (bundled full build already ships it) | `PENDING` | `FaditorEditorActivity.java` (export dialog), `export/ExportManager.java`, new `faditor/audio/LoudnessAnalyzer.java` | |
| `C5.E` | Ducking: sidechain envelope generator writing **real, editable** keyframes | `PENDING` | new `faditor/audio/Ducker.java`, `model/AudioClip.java` | |
| `C5.U` | "Duck under…" row in the track header menu | `PENDING` | `FaditorEditorActivity.java` | |
| `C5.X` | **Delete `duckAmount` or wire it** — §0 rule 6. Stored and read by nothing for months | `PENDING` | `model/Clip.java`, `ai/AIToolExecutor.java:2703` | |
| `C6` | Compressor gain-reduction bar (a compressor tuned blind is guesswork) | `PENDING` | `tools/AudioDrawerTabs.java` | |
| `C7` | A/B bypass — one tap mutes the whole FX chain for comparison | `PENDING` | `tools/ObjectDrawer.java` | |
| `C8` | **Wire "Clean Audio" in `C4` — do NOT hide it in the meantime.** **JOYRAPTOR RULED 2026-08-22:** he is the only user of the build right now, the checkbox is expected to become real within days, and spending a change on hiding-then-unhiding is waste. The §0-rule-6 concern is acknowledged and deliberately accepted for this window. **Do not re-raise; do not hide it.** If `C4` slips past the point where other people are using the build, this row reopens | `PENDING` (deferred into `C4`) | `FaditorEditorActivity.java:11082`, `model/ExportSettings.java:38-45` | |

### D — Differentiators

| ID | Item | Status | Files | Evidence |
|---|---|---|---|---|
| `D1` | Transcript **paragraph gutter** — §8 | `PENDING` | `transcript/TranscriptPanelView.java` | |
| `D2` | Paragraph reorder by dragging the gutter rail | `PENDING` | `transcript/TranscriptPanelView.java`, `FaditorEditorActivity.java` | |
| `D3` | Paragraph collapse → outline view for long episodes | `PENDING` | `transcript/TranscriptPanelView.java` | |
| `D4` | Named paragraph = chapter; export to YouTube chapter text | `PENDING` | `transcript/`, `export/` | |
| `D5` | Speaker labels / diarization | `PENDING` | `transcript/` | |
| `D6` | Beat detection → editable beat markers, snapping for audio *and* video cuts | `PENDING` | `waveform/Fft.java`, `timeline/EditorTimelineView.java` | |
| `D7` | Multi-speaker align by cross-correlation (clap sync) | `PENDING` | `faditor/audio/` | |
| `D8` | Audio-reactive property links (band → any keyframable property) | `PENDING` | see `PLAN_G9_LINK_ENGINE.md` | |
| `D9` | AI verbs for audio ("make the music quieter under my voice") | `PENDING` | `ai/AIToolExecutor.java` | |
| `D10` | **Extend the struck-word cut to standalone `AudioClip`s, and darken the struck tape.** **CORRECTED 2026-08-22 — the original row over-claimed.** Struck words ALREADY cut audio: `TranscriptPanelView.onStrikesChanged()` → `FaditorEditorActivity.syncRemovedSpansFromTranscript()` (*"update the clip's skip-list so preview skips it"*), plus `commitTranscript()`, `strikeFillers()` and the AI's `cut_all_fillers`. The REAL gap is narrow: `syncRemovedSpansFromTranscript` resolves its target via `getSelectedClip()`, so it serves a **video** clip's audio and bails for a standalone `AudioClip`. Work = route the same path for `AudioClip` transcripts, and render struck spans as darkened tape. **Do not rebuild the strike-cut mechanism; it ships.** | `BUILT` 8a23a5c7 | `FaditorEditorActivity.java:30322`, `model/AudioClip.java`, `project/ProjectStorage.java:2040/2688`, `layers/LayerRowRenderer.java` | `AudioClip` now carries `removedSpans` (copy ctor, getter/setter) + `syncRemovedSpansFromTranscript` routes to `AudioClip` when `getSelectedClip` bails + `drawAudioStruckTape` hatch + **HAND-serialization now mirrors Clip**: write `removedSpans` at `ProjectStorage:2040` (omit-at-default) and read at `:2688` (tolerant absence → empty list, old projects load, untouched byte-identical). TYPECHECK OK 632, `git cat-file -t 8a23a5c7` + `git branch --contains 8a23a5c7` verified. END STATE: strike words on standalone AudioClip, save, force-close, reopen — darkened spans still there and still cut. |
| `D11` | **Transcript-keyed ducking option for `C5.E`:** use Whisper word timings as the gate signal instead of (or alongside) amplitude sidechain — no pumping artifacts, deterministic, and it makes ducking *editable text*. Generator still writes ordinary keyframes per §3.5 | `PENDING` | `faditor/audio/Ducker.java`, `transcript/` | |

### E — Verification harness

| ID | Item | Status | Files | Evidence |
|---|---|---|---|---|
| `E1` | Extend `tasks/export_audio_probe.py` to assert preview LUFS == export LUFS | `PENDING` | `tasks/export_audio_probe.py` | |
| `E2` | Gesture flight-recorder lines for pill / fade-handle / trim precedence, per the `ROWGESTURE` pattern. **Must specifically log which zone won at a clip's top corner** — that is the one contested spot §4.1 exists to measure, and a false-fade-when-you-meant-trim is the failure JoyRaptor asked to be able to detect | `BUILT` c9897a57 | `layers/LayerRowRenderer.java`, `layers/LayerGestureController.java` | E2FADE logs `hitTest WON {TRIM_LEFT,TRIM_RIGHT,FADE_IN,FADE_OUT,DELETE,BODY}` at top 12dp band with x/y/localY/top/itemId; delete slop 2x radius check logged, fade checked before delete so FLog confirms fade wins. Follows ROWGESTURE pattern, TYPECHECK OK. **EXIT CONDITION (added 2026-08-22):** `LayerRowRenderer.E2_DEBUG` ships `true` on purpose — same precedent as the `ROWGESTURE` logging, which the gesture contract said to keep until the user confirmed. It is NOT permanent. Once JoyRaptor has exercised the corner on device and §4.1 is settled either way, flip `E2_DEBUG` to `false` or strip the block. **A flight recorder nobody turns off is just noise**, and leaving it is the same residue class as `A1.b`. Open `E2.x` when §4.1 resolves. |
| `E3` | Drawer opacity audit — every top drawer proved at `0x66000000` by screenshot | `PENDING` | screenshots | |
| `E4` | **Overlap-mix + crossfade probes**: export two overlapping lane clips and assert both are audible (sum ≈ non-clipping) — this is the `A8` proof; then assert a power dip at pill midpoint on a rendered cross-fade. Extends `tasks/export_audio_probe.py` | `PENDING` | `tasks/export_audio_probe.py` | |

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