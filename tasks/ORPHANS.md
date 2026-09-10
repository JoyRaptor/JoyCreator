# ORPHANS — work that was started and stopped, and is still wanted

**Swept:** 2026-09-09 (Claude/Opus) · **Method:** every claim below re-checked against the
code or git at HEAD, not taken from the document that made it.

JoyRaptor's rule (`STATE.md`): *"almost anything partially built is likely part of the finished
vision that I haven't had time to get around to yet."* Nothing here is dead. It is a rescue
list.

---

## 0. The finding that matters most: the documents lie in BOTH directions

This is why every agent rediscovers the app, and it is worth more than the list below.

**Unticked checkboxes are not evidence of unfinished work.**

| Document | Claims undone | Reality at HEAD |
|---|---|---|
| `todo_asset_browser.md` | 23 unchecked items | ✅ **Asset browser is built.** `AssetScanner`, `AssetBrowserPanel` exist and run. Nobody ticked the boxes. |
| `PLAN_SPEC_20260829_IMAGE_ANIM_PRESETS.md` | 13 unchecked items | ✅ **Landed** in `1bc9a273`. Plan never updated. |
| `DIAG_20260627` item 131 | "out-of-process export deferred" | ✅ **Built and proved** 2026-07-30 |
| `DOCKET_20260829` — "built, not wired" | `PcmSidecar`, `ScrubEngine`, `OnsetDetector` | ✅ **All three wired** during Word Sync V2 on 2026-08-30 — *one day after the docket was written.* Verified: each is referenced from 3–5 other files. |

**And the reverse:** work marked complete that was never seen on a screen. That is section 2.

**Consequence for every future agent and for `STATE.md`:** a checkbox is a wish, a commit is
a fact, and a screenshot is proof. Never grade this project by its checkboxes.

---

## 1. 🟠 THE BIG ONE — 14 features built, nobody has ever looked at them

`DOCKET_20260829.md` counted them: *"the building is well ahead of the looking."*
A 41-check device sweep ran on 2026-08-29 and came back **17 pass / 14 fail / 10 blocked.**
Since then **114 commits landed** — almost all in the mesh/bend lane. **The sweep was never
re-run.** These are still unlooked-at today.

### Built, committed, never seen (from the 2026-08-29 session)

| Feature | Commit | What to look at first |
|---|---|---|
| Keyframe shape language (5 glyphs drawn from the curve) | `88e73dc3` | **Ramp direction — most likely thing to be backwards** |
| A/V Sync calibration tab (click track + slider) | in `5e96b5c3` | Does the slider move the playhead against the waveform |
| Caption layers — model, storage, export, preview | staged | Three tracks rendering independently |
| Image `presetOwned` amber keyframes | `1bc9a273` | Amber appears, timeline drag converts, one undo restores |
| Fit / Fill quick actions | `1bc9a273` | Tall and wide image, both modes |
| Image opacity fade handles | `1bc9a273` | Fade multiplies rather than replaces |
| Image animation presets (pan / zoom / slide) | `1bc9a273` | No background peek at any point |
| Preview perf — content-keyed cache + texture quads | staged | Animated text under a blend appears at all |
| Sticky bookend keyframes follow a trim | `1bc9a273` | Drag the out point, amber key follows |

### Inherited from 2026-08-28, still unverified

Opacity keyframe delete + on-key dot · Caption font Import chip · Caption Fit tab
(OFF/UNIFORM/PER_CUE) · **Export GL frames** (`7727af0a` `7f8c283a` — timing and PSNR never
run) · Transcript source affordance

> **This is the highest-value block on the board.** Not one line of new code. It is a phone,
> an evening, and a checklist. Every hour here is worth three hours of building, because
> anything broken in this list ships broken.

---

## 2. 🔴 The open question this sweep raised and nobody answered

**2026-08-29, check #7: playback was silent and the playhead did not move.** Audio stayed
`state:idle` after eight taps; the previous build the same morning had worked. It was
recorded as a *"critical regression"* and it blocked six other checks.

**Nobody confirmed whether it was real.** The next day JoyRaptor device-verified the fade knobs
on the Note 9, which implies playback worked — so it may have been an automation artefact
(an agent tapping coordinates that were not the play button). But it was never closed.

**Do this first, it takes thirty seconds:** open a project with a music layer and press play.
If it plays, close the finding. If it does not, everything else waits.

---

## 3. 🟠 Started, stopped, still wanted

| Orphan | State | Where |
|---|---|---|
| **Object time-scrubber, non-text payloads** | Built and wired **for text overlays only**. Sprite, audio and PiP never extended. Plus 8 known UI gaps (clamp past timeline end, unstable preview box, badge styling, auto-scroll to new object, pan delay). | `SPEC_OBJECT_TIME_SCRUBBER.md` — has its own 2026-08-06 status correction, trust that over the body |
| **Loop / ping-pong verification** | Feature shipped; **7 acceptance checks never run** — frame inversion, no-black, degrade path, reverse-leg audio, >30s guard, live readout bubble, export parity | `PLAN_LOOP_PINGPONG.md:110-121` |
| **Layer gesture contract** | Mechanics work; whole-cluster hand-test never confirmed; temporary `ROWGESTURE` logging still in the build and meant to be stripped | `PLAN_LAYER_GESTURE_CONTRACT.md:136-138` |
| **M11 ripple/gap toggle** | Last unbuilt item of the Layers plan | `PLAN_LAYERS_V2.md:525` |
| **Asset browser split-warning arrow** | Spec says green at a boundary, **yellow when the insert will split a clip.** Always green — the split logic was never wired. | `DIAG_assetbrowser_20260626.md:60` |
| **Caption layers finishing work** | Drawer track list, pinch-to-size, full drawer retarget. Blocks image-anim-presets phase 3. | `DOCKET_20260829` §4 |
| **Luma key** | Spec complete, work not started. Chroma key shipped and is proved; luma is its twin. | `LEDGER.md` §3a |
| **Audio ducking** | Removed from the AI tools pending a mixer that can honour it. `set_clip_duck` removal comment still in the code. | ✔ verified in `AIToolExecutor` |
| **Horizontal reflow on a portrait canvas** | Landed, never seen in the one orientation that matters | Sweep check #40 |
| **~1:03 playback ceiling** | Never reproduced, no cap exists in code, never confirmed gone | `DOCKET_20260829` |

---

## 4. 🔵 Specced, never started

| Item | Size | Where |
|---|---|---|
| Adjustment layers / FX | 742 lines of spec | `SPEC_ADJUSTMENT_LAYERS_FX.md` |
| Playhead behaviour rework | 10 items | `PLAYHEAD_KINEMASTER_20260719.md` |
| Visualizer studio (Tier 3 custom HTML) | | `feature-visualizer-studio-spec.md` |
| Captions → GL, Visualizer → GL | Cheaper in principle, **unmeasured** — do not act on the guess | `FINDING_20260829_GL_ANIMATED_GAP.md` |
| Per-word rich text in a caption cue | Superseded for JoyRaptor by caption layers; still the honest answer for "bold reference, plain body" | `DOCKET_20260829` |
| Media-browser pin | Untouched since the fork began; JoyRaptor wants it revisited alongside the vault | `DOCKET_20260829` |

---

## 5. ✅ Recovered — do not re-investigate, these are done

Asset browser · image animation presets · out-of-process export · `PcmSidecar` ·
`ScrubEngine` · `OnsetDetector` (all three wired 2026-08-30) · GL transitions ·
audio-only export · project consolidation and zip bundling · relink catalog ·
mesh/bend through SPEC T.

---

## 6. What this changes about the plan

The orphans are **not a build backlog. They are a looking backlog.**

Fourteen features are finished and unseen. That is not weeks of work — it is one evening
with a phone and the checklist that already exists (`SPEC_20260829_DEVICE_VERIFY_ALL`).
And it must happen before launch, because a feature nobody has looked at is a feature that
ships broken.

**Recommended order:**
1. Answer the playback question (30 seconds)
2. Re-run the 41-check sweep on a current build (one evening)
3. Fix whatever it finds
4. *Then* the launch lanes in `ROADMAP.md`

Anything in section 3 or 4 that survives that is post-launch work, and all of it stays on
the roadmap. Nothing here gets archived.
