# PRIORITY — 2026-08-06 (night). One ordered list across all three sources.

> ## STATUS 2026-08-06 late — read before picking anything up
> **Tree compiles: TYPECHECK OK, 598 sources, 1638 classes. Nothing is committed.**
> Existing harnesses re-run and all green *after* these changes: `run-key.sh` 22/22,
> `run-matte.sh` all pass, `run-sequence.sh` 50/50 — so the multi-shape rewrite did not
> regress `MaskAnimator`, and the `blendPix` extraction is behaviour-neutral.
>
> **P1a (multi-shape masks) — model layer BUILT.** `CompositingSpec`, `MaskPathBuilder`,
> `MaskAnimator`, `FaditorProject`, `KeyframeSet`, new `MaskFold.java`, new
> `model/BlendModes.java`. `tasks/schema_mask_stamp.py` written and **13/13 green** — it
> reproduces the data loss, then proves the stamp stops it.
>
> ⚠ **A real bug was found and fixed in that work, worth knowing about:** `SCHEMA_VERSION`
> had been bumped to 13 and its javadoc *claimed* a conditional stamp, but
> `ProjectStorage`'s stamp table had **no v13 clause at all** and `usesIntersect()` was an
> orphan with no caller. Every intersect project would have stamped v8 and been silently
> degraded by an older build — precisely risk R2. Fixed via a single authority,
> `CompositingSpec.needsSchema13()` (which also catches the subtler diverged-`slot` trigger),
> called from a new `ProjectStorage.usesMultiShapeMaskFeatures`. **The lesson is the repo's
> own:** the doc asserted a safety property the code did not implement.
>
> **P1a harness — DONE. `run-mask.sh` is 54/54** (`MaskFoldTest` 21, `CompositingSpecTest`
> 33). The gate is proved *exhaustively*, not sampled: all 14 add/subtract arrangements up to
> three shapes stay on the shipped two-bucket path.
>
> **P1b mask tab UI — BUILT, typechecks, NOT yet seen on a phone.** Shape chips (1..n, +, −),
> Add/Subtract/Intersect, four presets, per-shape sliders rebuilt in place, per-shape object
> link, and "Key at playhead" writing slot-named tracks.
>
> **M0 IS CODE-COMPLETE.** Undo now covers the mask *and* chroma sliders (session snapshot,
> committed from both the drawer close hook and a retarget — `setOnClose` had no callers
> before). `docs/project-schema.md` documents feather / maskKeys / link* / mode / slot and the
> keyframe track names, and its version history — which had stopped at v10 while the code was
> on v12 — is backfilled through v13.
>
> **Full sweep at this commit:** `run-mask` 21/21 + 33/33 · `run-key` 22/22 (4 suites) ·
> `run-matte` all pass · `run-sequence` 100/100 + 50/50 · `schema_mask_stamp.py` 13/13.
> APK on the phone is current and built WITHOUT the debug flag.
>
> ### Round 2 — JoyRaptor's feedback, 2026-08-06 evening
> **DONE:**
> - **Off-stage positions.** Pos X/Y and mask X/Y now run **-100%..200%**
>   (`KeyframeSet.POS_MIN/POS_MAX`). A 0..1 range addressed the object's CENTRE, so
>   pan-on-from-offstage was never expressible, and a linked mask stopped following its object
>   the moment its own centre hit the edge. Fixed at every clamp: the props, the sliders,
>   `MaskAnimator.resolve`, `applyLink`, `CompositingSpec.fromJson`, and the preview DRAG.
>   Pinned by 6 new assertions in `MaskAnimatorTest` (26/26).
> - **`‹ ◇ ›` on every mask slider.** The diamond is deliberately inert and dimmed until
>   per-parameter mask keying exists — the tab's "Key at playhead" still keys all six at once.
> - **Layout.** Shape chips left + preset icons right-justified on one row; mode icons centred
>   on row two; both checkboxes on one line. Four rows became two.
> - **Icons.** 7 new drawables (`ic_mask_mode_*`, `ic_mask_preset_*`).
> - **"Soften edges" misalignment** — it was real: the shape sliders sat in a column nested
>   inside `root` and paid the 14dp side padding twice.
>
> **STILL OPEN from that round:**
> - **Off-screen ghost outline** — when an object or mask travels off-stage, draw an outline
>   where it *would* be so it can be found again. JoyRaptor's colours: **green for the object,
>   yellow for the mask.** Not started. This matters more now than it did before, because the
>   off-stage range above is exactly what makes an object easy to lose.
> - One judgement call to confirm: he asked for shapes + modes + presets on a **single** row;
>   it does not fit (~384dp of content on a ~360dp phone once a third shape exists), so it is
>   two rows. Confirm or overrule.
> - He described the intersect icon as "two solid circles, middle knocked out" — that glyph is
>   Pathfinder's EXCLUDE, the opposite result, so a true intersect is drawn instead. Confirm.
>
> ### 🖐 ONE THING THAT NEEDS JOYRAPTOR'S FINGER (20 seconds)
> The mask tab cannot be reached by adb input injection — see the P0.1 note below. To see the
> new UI: open **"bisect C long 2x"** → in the lane band, **hold** the picture-in-picture strip
> (the one with the video thumbnails, 4th row down) **until it lifts, then release without
> moving** → the drawer opens → **Mask** tab. Expect a `● 1  +` chip row at the top. Tap `+`
> and a second shape appears, offset to the right, with Add/Subtract/Intersect above the
> sliders. If that works, M0 is real.
>
> **P2 (fx/) — 3 of 8 classes only:** `FxParam`, `FxEffectDef`, `FxRegistry`. `FxInstance`,
> `FxStack`, `FxCompiler`, `FxUniforms`, `FxCost` are absent. It compiles because the
> reserved-name constants were moved onto `FxParam`; treat the package as a stub, not a
> foundation.
>
> **P0.1 device verification — BLOCKED. The hold gesture is NOT adb-drivable; stop trying.**
> Four injection strategies were tried and all fail identically. Save the next session the hour:
>
> | Tried | Result |
> |---|---|
> | `input tap` on the item | selects it (chips appear) — but a tap is only ever a select |
> | `input swipe x y x y 900` (same point) | no pickup |
> | `input motionevent DOWN` · `sleep 1.3` · `UP` (separate processes) | no pickup |
> | `input swipe x y x+2 y+1 1400` (in-slop, one process) | no pickup |
>
> The path is understood and is not the problem: `EditorTimelineView` arms
> `itemPickupRunnable` on the DOWN branch (`ITEM_PICKUP_MS = 450`, line ~6760),
> `beginPickup()` sets `pickupArmed`, and the UP resolves to `onItemMenuRequested` at
> `LayerGestureController:1892`. With `ROWGESTURE_DEBUG = true` and a rebuild, **zero
> ROWGESTURE lines are logged for any of the four**, so the touch never reaches the row-body
> pending state at all. The row was confirmed EXPANDED at the time (tapping its caret
> collapsed it), so a collapsed-row `MISS` is ruled out. Best remaining hypothesis: injected
> events lack something the pending-body path requires, and a real finger is the only way in.
>
> `ROWGESTURE_DEBUG` was flipped back to `false` and the phone reflashed, so the installed APK
> is clean.
>
> Fixture: project `129d8643` "bisect C long 2x" — a PiP with a mask + chroma key and no
> `link` keys, ideal for this test. Backed up before opening, drifted on autosave exactly as
> START_HERE §1 warns, and **restored byte-exact to `md5 c2bfa8c6`** (twice).

Merges `START_HERE_20260806b.md` §3, `SPEC_ADJUSTMENT_LAYERS_FX.md` M0–M7, and the multi-shape
mask work (which **is** that spec's M0 — it was never a separate item).

Branch `joy-creator`, HEAD `6313f26`. Sandbox Note 9 `SANDBOX_SERIAL` attached, Note 20 absent.

**The ordering rule used throughout:** cheapest regression-catch first, then the things that are
*load-bearing for later milestones*, then size. Anything needing a JoyRaptor decision is parked in P5
rather than blocking.

---

## P0 — Verification owed (device). Do before touching anything it covers.

START_HERE §3A. These are built-but-never-touched-on-a-phone. Cheapest possible catch, and #6 must
happen **before** P1 rewrites the very tab it tests.

| # | Item | Why this rank |
|---|---|---|
| 0.1 | §3A.6 **Mask tab** link + "◆ Key at playhead" → confirm `"link": true` + `linkBase*` in project.json | **Blocks P1.** P1 rebuilds `maskTab`; verify current behaviour first or you can't tell a P1 regression from a pre-existing bug. |
| 0.2 | §3A.1 ABSOLUTE left-trim on device | Harness-pinned only; headline gesture |
| 0.3 | §3A.3 Copy-on-write independence | Shipped today, unproven by hand |
| 0.4 | §3A.2 §9c live readout (mid-gesture capture) | Needs the background-draganddrop trick |
| 0.5 | §3A.4 MISSING placeholder (rename a frame file) | Cheap |
| 0.6 | §3A.5 AI sequence tools (`describe_sequence` / `edit_sequence`) | Key exists now; also probes §3B |

## P1 — Multi-shape masks (= SPEC_ADJUSTMENT_LAYERS_FX **M0**)

JoyRaptor chose "ship first, quick win." It is also **load-bearing**: the feather-cache LRU inside it
must land before any second masked object can exist (spec R4), and the stable-`slot` rule it
establishes is what M1's FX cards reuse.

Ships three things beyond the feature itself:
- the **feather-cache LRU** (M4 hard dependency),
- **undo for mask AND chroma sliders** — `PipDrawerTabs.Host.recordUndo` is declared and never
  called, so those sliders have no undo at all today,
- schema v13, stamped **conditionally** so add/subtract-only projects stay byte-identical.

Split for parallel work: **P1a** model + harness (`CompositingSpec`, `MaskPathBuilder`,
`MaskAnimator`, `run-mask.sh`) is pure JVM and independent. **P1b** is the `maskTab` UI and undo,
and must wait for P0.1.

## P2 — FX foundation (**M1**), pure model

New android-free `fx/` package + compiler + registry + `BlendModes` extraction. Touches **no
existing render path** and is fully JVM-testable, so it can run concurrently with P0 and P1 with
zero conflict risk. Highest-value use of a parallel agent in this whole list.

## P3 — The two small requested items, and the grade fix

| # | Item | Source |
|---|---|---|
| 3.1 | **Duplicate-layer button** — own lane, same time position | START_HERE §3D, JoyRaptor's request, decision already made |
| 3.2 | **M2 — repackage the colour grade** | Spec §3. Fixes two *shipping* bugs: export double-applies exposure + temp/tint whenever vignette/grain is on, and the preview vignette is a total no-op. Also JoyRaptor's explicit "incorporate what we got before re-inventing the wheel." |

3.2 outranks everything below it because it is the only milestone that checks the new compiler
against a **known-good shipping feature** before it is trusted with a new object type.

## P4 — The rest of the adjustment-layer build, in spec order

M3 object+schema → M4 export rendering → M5 preview rendering → M6 FX tab UI. M4+M5 together are
the first user-visible adjustment layer; M6 is the first pleasant one. Do not reorder — the spec's
§11 explains why M2 precedes M3.

## P4b — FF-B sprite AI tools

`set_sprite_grid`, `label_sprite_cells`, `author_sprite_animation`, `apply_sprite_proposal`.
START_HERE §5 ranks this #2, and it is the largest genuinely-missing block. Demoted below P3 only
because P3's two items are small and one of them fixes bugs that ship today. Promote it if the
adjustment-layer work stalls on a decision.

## P5 — Parked: needs JoyRaptor, or low yield

- Delete or revive `MaskKeyPanel` (466 lines, dead) — **his call**. Note: P1b *reuses its
  session-snapshot undo idiom*, so read it before deleting.
- Composed-frame vision work (overlays included) — **his call**, real effort.
- Tablet `sw600dp` layouts — **his call**.
- Vision round-trip against a pinned slug (`nvidia/nemotron-nano-12b-v2-vl:free`) — cheap, do it
  opportunistically during P0.6.
- Remaining orphans (`isCellMissing`, `getOrder`, `resort`, …) — sweep in one pass, later.
- `SequenceFrameCache` ~144MB ceiling; scrubber B-CLAMP / B-PREVIEW; `SequenceDetector` 600 cap.
- M7 per-object FX on PiP/text.

---

## Execution shape (3 agents max, as instructed)

| Lane | Owner | Work | Conflict risk |
|---|---|---|---|
| Device | **me, directly** | P0 — serial resource, one phone, needs judgement mid-gesture | n/a |
| Agent A | subagent | **P1a** mask model + harness | `model/` only |
| Agent B | subagent | **P2** new `fx/` package + `BlendModes` extraction | new files + one ~30-line extraction |
| Agent C | subagent, later | adversarial review of A+B before commit | read-only |

A and B are file-disjoint by construction. P1b (UI) is mine, after P0.1 clears. The review lane is
deliberately held back — START_HERE §6 records that a review agent over a session diff found 13
issues, 8 real, two of which made headline gestures silently inert.
